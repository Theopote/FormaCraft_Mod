package com.formacraft.common.component.community;

import com.formacraft.common.component.ComponentPackageIO;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.formacraft.common.component.community.CommunityJson.*;

/** Independent immutable raw-object cache. Caller supplies a private root; no library installation. */
public final class CommunityDownloadCache {
    private CommunityDownloadCache() {}
    public static final int MAX_OBJECTS = 128, MAX_RECEIPTS = 256, MAX_RECEIPT_BYTES = 256 * 1024;
    public static final long MAX_OBJECT_BYTES = 64L * 1024 * 1024;
    private static final String SCHEMA = "formacraft.community.download-receipt.v1", RAW = "original.fccomponent.json", RECEIPT = "receipt.json";
    public record Cached(String receiptId, Instant verifiedAt, CommunityReleaseDetails details, CommunityPackages.Download download) {}
    @FunctionalInterface public interface Commit { void run() throws IOException; }
    @FunctionalInterface public interface CommitGate { void publish(Commit commit) throws IOException; }

    /** Independent evidence copy: deleting the original cache cannot invalidate the target vault. */
    public static String pin(Path targetRoot, Cached cached, ComponentPackageIO.Environment environment) throws IOException {
        if (cached == null || !receiptId(cached.details().descriptor()).equals(cached.receiptId())) throw new CommunityPackages.Failure("cache_corrupt");
        return save(targetRoot, new CommunitySearchHttpClient.Fetched(cached.details(), cached.download()), environment,
                Clock.fixed(cached.verifiedAt(), ZoneOffset.UTC));
    }

    public static String receiptId(CommunityPackages.Descriptor descriptor) {
        var ref = descriptor.releaseRef();
        String identity = String.join("\n", SCHEMA, ref.authorityId(), ref.resourceId(), ref.releaseId(), ref.packageSha256(), ref.payloadSha256(), ref.manifestSha256(),
                descriptor.fingerprints().structure(), descriptor.fingerprints().configuration(), Integer.toString(descriptor.byteLength()), descriptor.packageSchema());
        return "receipt_" + CommunityPackages.sha256(identity.getBytes(StandardCharsets.UTF_8));
    }

    public static synchronized String save(Path cacheRoot, CommunitySearchHttpClient.Fetched fetched,
                                            ComponentPackageIO.Environment environment, Clock clock) throws IOException {
        return save(cacheRoot,fetched,environment,clock,Commit::run);
    }
    public static synchronized String save(Path cacheRoot, CommunitySearchHttpClient.Fetched fetched,
                                            ComponentPackageIO.Environment environment, Clock clock, CommitGate gate) throws IOException {
        Objects.requireNonNull(fetched); Objects.requireNonNull(clock);
        Objects.requireNonNull(gate);
        var details = fetched.details(); var source = fetched.download();
        if (!details.available()) throw new CommunityPackages.Failure("release_unavailable");
        details.verify(source);
        byte[] bytes = source.originalBytes();
        var checked = CommunityPackages.receive(new ByteArrayInputStream(bytes), details.descriptor(), environment); details.verify(checked);
        String id = receiptId(details.descriptor());
        var metadata = metadata(id, details, clock.instant()); byte[] receiptBytes = CommunityReleaseDetails.encode(metadata, MAX_RECEIPT_BYTES);
        Path root = root(cacheRoot, true), objects = directory(root, "objects", true), receipts = directory(root, "receipts", true);
        Path receipt = receipts.resolve(id);
        if (Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) { read(cacheRoot, id, environment); return id; }
        if (usage(receipts, RECEIPT, MAX_RECEIPTS, (long)MAX_RECEIPTS * MAX_RECEIPT_BYTES)[0] >= MAX_RECEIPTS)
            throw new CommunityPackages.Failure("cache_capacity");
        String hash = details.descriptor().releaseRef().packageSha256();
        if (Files.exists(objects.resolve(hash), LinkOption.NOFOLLOW_LINKS)) {
            byte[] existing = file(directory(objects, hash, false), RAW, ComponentPackageIO.MAX_PACKAGE_BYTES);
            if (!Arrays.equals(existing, bytes)) throw new CommunityPackages.Failure("cache_corrupt");
        } else {
            long[] usage = usage(objects, RAW, MAX_OBJECTS, MAX_OBJECT_BYTES);
            if (usage[0] >= MAX_OBJECTS || bytes.length > MAX_OBJECT_BYTES - usage[1]) throw new CommunityPackages.Failure("cache_capacity");
            atomicDirectory(objects, hash, RAW, bytes, gate);
        }
        atomicDirectory(receipts, id, RECEIPT, receiptBytes, gate);
        return id;
    }

    /** Historical receipt is not online availability; every read rechecks bytes and current environment. */
    public static synchronized Cached read(Path cacheRoot, String id, ComponentPackageIO.Environment environment) throws IOException {
        if (id == null || !id.matches("receipt_[0-9a-f]{64}")) throw new CommunityPackages.Failure("invalid_receipt_id");
        Path root = root(cacheRoot, false), receipts = directory(root, "receipts", false);
        try {
            var metadata = CommunityJson.read(file(directory(receipts, id, false), RECEIPT, MAX_RECEIPT_BYTES), MAX_RECEIPT_BYTES);
            CommunityReleaseDetails.exact(metadata, "schema", "id", "descriptor", "releaseDetail", "verifiedAt", "checkedEnvironment");
            if (!SCHEMA.equals(text(metadata.get("schema"), 128)) || !id.equals(text(metadata.get("id"), 128))) throw new IOException("Receipt identity");
            var descriptor = CommunityReleaseDetails.descriptor(object(metadata.get("descriptor")));
            if (!receiptId(descriptor).equals(id)) throw new IOException("Receipt fingerprint");
            var details = CommunityReleaseDetails.decode(CommunityReleaseDetails.encode(object(metadata.get("releaseDetail")), CommunityReleaseDetails.MAX_BYTES), descriptor);
            if (!details.available()) throw new IOException("Invalid historical delivery state");
            Instant verifiedAt = Instant.parse(text(metadata.get("verifiedAt"), 64));
            var checkedEnvironment = object(metadata.get("checkedEnvironment")); CommunityReleaseDetails.exact(checkedEnvironment, "minecraftVersion", "requiredMods");
            if (!details.manifest().minecraftVersion().equals(text(checkedEnvironment.get("minecraftVersion"), 64))
                    || !details.manifest().requiredMods().equals(CommunityReleaseDetails.stringMap(object(checkedEnvironment.get("requiredMods")), 128, 128)))
                throw new IOException("Environment evidence mismatch");
            Path objects = directory(root, "objects", false), object = directory(objects, descriptor.releaseRef().packageSha256(), false);
            byte[] bytes = file(object, RAW, ComponentPackageIO.MAX_PACKAGE_BYTES);
            var download = CommunityPackages.receive(new ByteArrayInputStream(bytes), descriptor, environment); details.verify(download);
            return new Cached(id, verifiedAt, details, download);
        } catch (CommunityPackages.Failure failed) {
            if (Set.of("environment_required", "environment_mismatch").contains(failed.code())) throw failed;
            throw new CommunityPackages.Failure("cache_corrupt", failed);
        } catch (IOException | RuntimeException invalid) { throw new CommunityPackages.Failure("cache_corrupt", invalid); }
    }
    private static JsonObject metadata(String id, CommunityReleaseDetails details, Instant verifiedAt) throws IOException {
        var root = new JsonObject(); root.addProperty("schema", SCHEMA); root.addProperty("id", id);
        root.add("descriptor", CommunityReleaseDetails.descriptorJson(details.descriptor()));
        root.add("releaseDetail", CommunityJson.read(details.toBytes(), CommunityReleaseDetails.MAX_BYTES)); root.addProperty("verifiedAt", verifiedAt.toString());
        var environment = new JsonObject(); environment.addProperty("minecraftVersion", details.manifest().minecraftVersion());
        var mods = new JsonObject(); new TreeMap<>(details.manifest().requiredMods()).forEach(mods::addProperty);
        environment.add("requiredMods", mods); root.add("checkedEnvironment", environment); return root;
    }
    private static Path root(Path path, boolean create) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (create) Files.createDirectories(absolute);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) || !absolute.toRealPath().equals(absolute)) throw new IOException("Cache root redirects");
        return absolute;
    }
    private static Path directory(Path parent, String name, boolean create) throws IOException {
        Path child = parent.resolve(name);
        if (create && !Files.exists(child, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(child);
        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) || !child.toRealPath().equals(parent.toRealPath().resolve(name))) throw new IOException("Cache directory redirects");
        return child;
    }
    private static byte[] file(Path parent, String name, int max) throws IOException {
        Path file = parent.resolve(name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().equals(parent.toRealPath().resolve(name))) throw new IOException("Cache file redirects");
        try (var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(max + 1); if (bytes.length > max) throw new IOException("Cache file limit"); return bytes;
        }
    }
    private static long[] usage(Path parent, String fileName, int maxCount, long maxBytes) throws IOException {
        long count = 0, bytes = 0;
        try (var entries = Files.newDirectoryStream(parent)) {
            for (Path entry : entries) {
                if (++count > maxCount) throw new CommunityPackages.Failure("cache_capacity");
                Path checked = directory(parent, entry.getFileName().toString(), false), source = checked.resolve(fileName);
                if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Cache file redirects");
                    bytes += Files.size(source); if (bytes > maxBytes) throw new CommunityPackages.Failure("cache_capacity");
                }
            }
        }
        return new long[]{count, bytes};
    }
    private static void atomicDirectory(Path parent, String id, String fileName, byte[] bytes, CommitGate gate) throws IOException {
        Path staging = Files.createTempDirectory(parent, ".pending-");
        try {
            Files.write(staging.resolve(fileName), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            gate.publish(() -> Files.move(staging, parent.resolve(id), StandardCopyOption.ATOMIC_MOVE));
        } finally {
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Cache staging redirects");
                Files.deleteIfExists(staging.resolve(fileName)); Files.delete(staging);
            }
        }
    }
}
