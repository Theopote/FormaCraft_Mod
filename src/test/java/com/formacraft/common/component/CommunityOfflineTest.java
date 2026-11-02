package com.formacraft.common.component;

import com.formacraft.common.component.community.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommunityOfflineTest {
    private static final ComponentPackageIO.Environment ENV = new ComponentPackageIO.Environment("1.21.10", Map.of());
    private record Fixture(byte[] bytes, CommunityPackages.Descriptor descriptor) {}
    private Fixture fixture(String release, String author, String block, String tag) throws Exception {
        var def = new ComponentDefinition(); def.id = "column"; def.name = author;
        def.category = ComponentCategory.COLUMN; def.sourceOrigin = "CAPTURED"; def.tags = List.of(tag);
        def.size = new ComponentDefinition.Size(); def.size.w = def.size.d = def.size.h = 1;
        def.anchor = new ComponentDefinition.Anchor(); def.anchor.facing = "SOUTH";
        var entry = new ComponentDefinition.BlockEntry(); entry.block = block; def.blocks = List.of(entry);
        var manifest = new ComponentPackageIO.Manifest(author, "1", author, "LicenseRef-" + author,
                ComponentPackageIO.TrainingPermission.UNSPECIFIED, "1.21.10", Map.of(), Map.of(), List.of());
        var pack = ComponentPackageIO.create(def, manifest, ENV);
        // Deliberately noncanonical envelope bytes: the download must preserve these exactly.
        byte[] bytes = (" \n" + new String(pack.toBytes(), StandardCharsets.UTF_8) + "\n ").getBytes(StandardCharsets.UTF_8);
        var ref = new CommunityPackages.ReleaseRef("test", "res_" + author, release,
                CommunityPackages.sha256(bytes), pack.sha256(), pack.manifestSha256());
        return new Fixture(bytes, new CommunityPackages.Descriptor(ref, bytes.length, ComponentPackageIO.SCHEMA,
                ComponentContentIdentity.fingerprints(def)));
    }
    private OfflineCommunityIndex index(Clock clock, Fixture... fixtures) throws Exception {
        var index = new OfflineCommunityIndex("test", clock);
        for (var fixture : fixtures) index.add(fixture.descriptor, fixture.bytes);
        return index;
    }
    private static void failure(String code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(CommunityPackages.Failure.class, operation).code());
    }
    private static OfflineCommunityIndex.Query all() { return new OfflineCommunityIndex.Query(null, Set.of()); }

    @Test void retainsOriginalBytesAndExactSelectedSourceWithoutInstalling() throws Exception {
        var a = fixture("rel_a", "alice", "minecraft:stone", "a");
        var b = fixture("rel_b", "bob", "minecraft:stone", "b");
        var index = index(Clock.systemUTC(), a, b);
        var page = index.search(all(), 10, null);
        assertEquals(1, page.items().size()); assertEquals(2, page.items().getFirst().matchingSourceCount());
        var download = index.download(b.descriptor.releaseRef(), ENV);
        assertEquals("bob", download.verified().manifest().author());
        assertEquals("LicenseRef-bob", download.verified().manifest().license());
        assertArrayEquals(b.bytes, download.originalBytes());
        assertFalse(Arrays.equals(download.originalBytes(), download.verified().toBytes()));
        byte[] mutable = download.originalBytes(); mutable[0] = 0;
        assertArrayEquals(b.bytes, download.originalBytes());
        assertEquals(b.descriptor.releaseRef().payloadSha256() + "_" + b.descriptor.releaseRef().manifestSha256(), download.snapshotKey());
        assertEquals("unknown", page.items().getFirst().preferredCandidate().actualFit());
        assertEquals(ComponentPackageIO.TrainingPermission.UNSPECIFIED, download.verified().manifest().trainingPermission());
    }

    @Test void hardTagsFilterSourcesBeforeGroupingAndWithdrawalRechecksCounts() throws Exception {
        var a = fixture("rel_a", "alice", "minecraft:stone", "a");
        var b = fixture("rel_b", "bob", "minecraft:stone", "b");
        var index = index(Clock.systemUTC(), a, b);
        var page = index.search(new OfflineCommunityIndex.Query("formacraft:column", Set.of("b")), 10, null);
        assertEquals(b.descriptor, page.items().getFirst().preferredCandidate().descriptor());
        assertEquals(1, page.items().getFirst().matchingSourceCount());
        index.setAvailable("rel_b", false);
        failure("release_unavailable", () -> index.download(b.descriptor.releaseRef(), ENV));
        assertEquals(1, index.search(all(), 10, null).items().getFirst().matchingSourceCount());
        failure("release_exists", () -> index.add(b.descriptor, b.bytes));
    }

    @Test void snapshotIgnoresNewReleasesButRechecksWithdrawalAndCanReplay() throws Exception {
        var a = fixture("rel_a", "alice", "minecraft:stone", "a");
        var b = fixture("rel_b", "bob", "minecraft:oak_planks", "b");
        var c = fixture("rel_c", "carol", "minecraft:glass", "c");
        var index = index(Clock.systemUTC(), a, b, c);
        var first = index.search(all(), 1, null); assertNotNull(first.nextCursor());
        var d = fixture("rel_d", "dana", "minecraft:bricks", "d"); index.add(d.descriptor, d.bytes);
        index.setAvailable("rel_b", false);
        var second = index.search(all(), 1, first.nextCursor());
        assertEquals(c.descriptor, second.items().getFirst().preferredCandidate().descriptor());
        assertNull(second.nextCursor());
        assertEquals(second, index.search(all(), 1, first.nextCursor()));
        failure("cursor_mismatch", () -> index.search(new OfflineCommunityIndex.Query(null, Set.of("b")), 1, first.nextCursor()));
        failure("cursor_mismatch", () -> index.search(all(), 2, first.nextCursor()));
    }

    @Test void cursorExpiresAtBoundaryAndInvalidPagesFail() throws Exception {
        class MutableClock extends Clock {
            Instant now = Instant.parse("2026-11-03T00:00:00Z");
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now; }
        }
        var clock = new MutableClock();
        var index = index(clock, fixture("rel_a", "alice", "minecraft:stone", "a"), fixture("rel_b", "bob", "minecraft:glass", "b"));
        String cursor = index.search(all(), 1, null).nextCursor(); clock.now = clock.now.plusSeconds(300);
        failure("cursor_expired", () -> index.search(all(), 1, cursor));
        failure("invalid_page_size", () -> index.search(all(), 21, null));
    }

    @Test void downloadChecksEachIndependentDigestAndEnvironment() throws Exception {
        var f = fixture("rel_a", "alice", "minecraft:stone", "a");
        var ref = f.descriptor.releaseRef();
        var wrongPayload = new CommunityPackages.ReleaseRef("test", "res_alice", "rel_a", ref.packageSha256(), "0".repeat(64), ref.manifestSha256());
        var wrongManifest = new CommunityPackages.ReleaseRef("test", "res_alice", "rel_a", ref.packageSha256(), ref.payloadSha256(), "0".repeat(64));
        failure("payload_hash_mismatch", () -> CommunityPackages.inspect(f.bytes, new CommunityPackages.Descriptor(wrongPayload, f.bytes.length, ComponentPackageIO.SCHEMA, f.descriptor.fingerprints())));
        failure("manifest_hash_mismatch", () -> CommunityPackages.inspect(f.bytes, new CommunityPackages.Descriptor(wrongManifest, f.bytes.length, ComponentPackageIO.SCHEMA, f.descriptor.fingerprints())));
        byte[] changed = f.bytes.clone(); changed[0] = '!';
        failure("package_hash_mismatch", () -> CommunityPackages.inspect(changed, f.descriptor));
        failure("environment_mismatch", () -> CommunityPackages.receive(new ByteArrayInputStream(f.bytes), f.descriptor, new ComponentPackageIO.Environment("other", Map.of())));
        failure("environment_required", () -> CommunityPackages.receive(new ByteArrayInputStream(f.bytes), f.descriptor, null));
        failure("object_changed", () -> index(Clock.systemUTC(), f).download(wrongManifest, ENV));
    }

    @Test void receiveDoesNotReadUnboundedOrAcceptShortBody() throws Exception {
        var f = fixture("rel_a", "alice", "minecraft:stone", "a");
        class Endless extends InputStream {
            int reads;
            public int read() { reads++; return 32; }
        }
        var input = new Endless();
        failure("download_incomplete", () -> CommunityPackages.receive(input, f.descriptor, ENV));
        assertEquals(f.bytes.length + 1, input.reads);
        failure("download_incomplete", () -> CommunityPackages.receive(new ByteArrayInputStream(Arrays.copyOf(f.bytes, f.bytes.length - 1)), f.descriptor, ENV));
    }

    @Test void corruptedEncodingAndInnerPayloadAreRejectedEvenWithMatchingOuterHash() throws Exception {
        var f = fixture("rel_a", "alice", "minecraft:stone", "a");
        byte[] invalidEncoding = f.bytes.clone(); invalidEncoding[0] = (byte) 0xff;
        String json = new String(f.bytes, StandardCharsets.UTF_8);
        byte[] invalidPayload = json.replace("minecraft:stone", "minecraft:glass").getBytes(StandardCharsets.UTF_8);
        for (byte[] bad : List.of(invalidEncoding, invalidPayload)) {
            var ref = f.descriptor.releaseRef();
            var changed = new CommunityPackages.ReleaseRef(ref.authorityId(), ref.resourceId(), ref.releaseId(),
                    CommunityPackages.sha256(bad), ref.payloadSha256(), ref.manifestSha256());
            var descriptor = new CommunityPackages.Descriptor(changed, bad.length, ComponentPackageIO.SCHEMA, f.descriptor.fingerprints());
            failure("invalid_package", () -> CommunityPackages.receive(new ByteArrayInputStream(bad), descriptor, ENV));
        }
    }

    @Test void configurationClaimsAreRecomputedAndUnsupportedVersionsAreRejected() throws Exception {
        var f = fixture("rel_a", "alice", "minecraft:stone", "a");
        var falseClaim = new ComponentContentIdentity.Fingerprints(f.descriptor.fingerprints().structure(), "configuration-v1:sha256:" + "0".repeat(64));
        var descriptor = new CommunityPackages.Descriptor(f.descriptor.releaseRef(), f.bytes.length, ComponentPackageIO.SCHEMA, falseClaim);
        failure("fingerprint_mismatch", () -> CommunityPackages.inspect(f.bytes, descriptor));
        assertThrows(IllegalArgumentException.class, () -> new CommunityPackages.Descriptor(f.descriptor.releaseRef(), f.bytes.length, "future", falseClaim));
        assertThrows(IllegalArgumentException.class, () -> new CommunityPackages.Descriptor(f.descriptor.releaseRef(), ComponentPackageIO.MAX_PACKAGE_BYTES + 1, ComponentPackageIO.SCHEMA, falseClaim));
    }

    @Test void fixtureOwnershipAndAuthorityAreIsolated() throws Exception {
        var f = fixture("rel_a", "alice", "minecraft:stone", "a");
        var index = index(Clock.systemUTC(), f); byte[] original = f.bytes.clone(); f.bytes[0] = 0;
        assertArrayEquals(original, index.download(f.descriptor.releaseRef(), ENV).originalBytes());
        failure("authority_mismatch", () -> new OfflineCommunityIndex("other", Clock.systemUTC()).add(f.descriptor, original));
        assertThrows(IllegalArgumentException.class, () -> new CommunityPackages.ReleaseRef("../x", "r", "v", "0".repeat(64), "0".repeat(64), "0".repeat(64)));
    }
}
