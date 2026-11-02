package com.formacraft.common.component;

import com.formacraft.common.component.community.*;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CommunityDownloadsTest {
    @TempDir Path temp;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC);
    private static final ComponentPackageIO.Environment ENV = new ComponentPackageIO.Environment("1.21.10", Map.of("decor_test", "1", "unrelated", "42"));
    private record Fixture(byte[] bytes, CommunityPackages.Descriptor descriptor) {}
    private static Fixture fixture(String id, String publisher) throws Exception {
        var def = new ComponentDefinition(); def.id = "column"; def.name = "Column"; def.category = ComponentCategory.COLUMN; def.sourceOrigin = "CAPTURED";
        def.size = new ComponentDefinition.Size(); def.size.w = def.size.d = def.size.h = 1;
        def.anchor = new ComponentDefinition.Anchor(); def.anchor.facing = "SOUTH";
        var block = new ComponentDefinition.BlockEntry(); block.block = "minecraft:stone"; def.blocks = List.of(block);
        var manifest = new ComponentPackageIO.Manifest(publisher, "1", publisher, "LicenseRef-" + publisher + "\nTerms",
                ComponentPackageIO.TrainingPermission.DENIED, "1.21.10", Map.of("decor_test", "1"), Map.of(), List.of());
        var pack = ComponentPackageIO.create(def, manifest, ENV);
        byte[] bytes = (" \n" + new String(pack.toBytes(), StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
        var ref = new CommunityPackages.ReleaseRef("test", "res_" + publisher, id, CommunityPackages.sha256(bytes), pack.sha256(), pack.manifestSha256());
        return new Fixture(bytes, new CommunityPackages.Descriptor(ref, bytes.length, ComponentPackageIO.SCHEMA, ComponentContentIdentity.fingerprints(def)));
    }
    private static OfflineCommunityIndex index(Fixture... fixtures) throws Exception {
        var index = new OfflineCommunityIndex("test", CLOCK); for (var f : fixtures) index.add(f.descriptor, f.bytes); return index;
    }
    private static CommunitySearchHttpClient.Fetched fetched(OfflineCommunityIndex index, Fixture f) throws Exception {
        return new CommunitySearchHttpClient.Fetched(CommunityReleaseDetails.decode(index.releaseDetails(f.descriptor.releaseRef()), f.descriptor), index.download(f.descriptor.releaseRef(), ENV));
    }
    private static void failure(String code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(CommunityPackages.Failure.class, operation).code());
    }
    private static JsonObject json(byte[] bytes) { return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(); }
    private static byte[] bytes(JsonObject value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
    private static final class Server implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService executor = Executors.newCachedThreadPool();
        final AtomicReference<String> mode = new AtomicReference<>("normal"); final AtomicInteger blobs = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        Server(OfflineCommunityIndex index, Fixture... fixtures) throws IOException {
            Map<String, Fixture> byId = new HashMap<>(); for (var f : fixtures) byId.put(f.descriptor.releaseRef().releaseId(), f);
            server.createContext("/api/v1/", exchange -> {
                try {
                    assertEquals("GET", exchange.getRequestMethod()); assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                    String path = exchange.getRequestURI().getPath(), scenario = mode.get();
                    boolean detail = path.startsWith("/api/v1/releases/");
                    String id = detail ? path.substring("/api/v1/releases/".length()) : exchange.getRequestURI().getQuery().substring("releaseId=".length());
                    var f = byId.get(id); if (f == null) throw new CommunityPackages.Failure("release_unavailable");
                    if (detail) {
                        byte[] body = index.releaseDetails(f.descriptor.releaseRef());
                        if (scenario.equals("metadata")) { var root = json(body); root.getAsJsonObject("immutableRelease").getAsJsonObject("sourceManifest").addProperty("license", "False grant"); body = bytes(root); }
                        if (scenario.equals("detail_changed")) { var root = json(body); root.getAsJsonObject("immutableRelease").getAsJsonObject("descriptor").addProperty("byteLength", f.bytes.length + 1); body = bytes(root); }
                        if (scenario.equals("withdraw_between")) index.setAvailable(id, false);
                        reply(exchange, 200, body); return;
                    }
                    blobs.incrementAndGet();
                    assertEquals("/api/v1/package-blobs/" + f.descriptor.releaseRef().packageSha256(), path);
                    byte[] body = index.packageBytes(f.descriptor.releaseRef());
                    exchange.getResponseHeaders().set("ETag", "\"" + (scenario.equals("etag") ? "0".repeat(64) : f.descriptor.releaseRef().packageSha256()) + "\"");
                    if (scenario.equals("hash")) { body = body.clone(); body[0] = '!'; }
                    if (scenario.equals("length")) body = Arrays.copyOf(body, body.length + 1);
                    if (scenario.equals("cancel")) {
                        exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(body[0]); output.flush(); started.countDown();
                            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                            output.write(body, 1, body.length - 1);
                        }
                    } else reply(exchange, scenario.equals("range") ? 206 : 200, body);
                } catch (CommunityPackages.Failure failed) {
                    var root = new JsonObject(); root.addProperty("requestId", exchange.getRequestHeaders().getFirst("X-Formacraft-Request-Id"));
                    root.addProperty("code", failed.code()); root.addProperty("message", "Fixture unavailable"); root.addProperty("retryable", false); reply(exchange, 404, bytes(root));
                } catch (IOException aborted) { exchange.close(); }
            }); server.setExecutor(executor); server.start();
        }
        URI base() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"); }
        public void close() { release.countDown(); server.stop(0); executor.shutdownNow(); }
        private static void reply(HttpExchange exchange, int status, byte[] bytes) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        }
    }

    @Test void selectedSourceRoundTripsThroughDetailsAndHttpWithoutReserialization() throws Exception {
        var a = fixture("rel_a", "alice"); var b = fixture("rel_b", "bob"); var index = index(a, b);
        try (var server = new Server(index, a, b); var client = new CommunitySearchHttpClient("test", server.base())) {
            var fetched = client.download(b.descriptor, ENV, new CommunitySearchHttpClient.Cancellation());
            assertEquals("bob", fetched.download().verified().manifest().namespace()); assertArrayEquals(b.bytes, fetched.download().originalBytes());
            assertFalse(Arrays.equals(b.bytes, fetched.download().verified().toBytes())); fetched.details().verify(fetched.download());
            assertEquals(1, server.blobs.get());
            var detail = json(client.details(b.descriptor, new CommunitySearchHttpClient.Cancellation()).toBytes());
            assertTrue(detail.getAsJsonObject("immutableRelease").get("platformPublisher").isJsonNull());
        }
    }

    @Test void detailsAndBlobBothRecheckWithdrawalWithoutFallback() throws Exception {
        for (boolean between : List.of(false, true)) {
            var f = fixture("rel_a", "alice"); var index = index(f);
            try (var server = new Server(index, f); var client = new CommunitySearchHttpClient("test", server.base())) {
                if (between) server.mode.set("withdraw_between"); else index.setAvailable("rel_a", false);
                failure("release_unavailable", () -> client.download(f.descriptor, ENV, new CommunitySearchHttpClient.Cancellation()));
                assertEquals(between ? 1 : 0, server.blobs.get());
            }
        }
    }

    @Test void rejectsChangedDetailEtagLengthHashMetadataAndPartialBody() throws Exception {
        for (String scenario : List.of("detail_changed", "etag", "length", "hash", "metadata", "range")) {
            var f = fixture("rel_a", "alice");
            try (var server = new Server(index(f), f); var client = new CommunitySearchHttpClient("test", server.base())) {
                server.mode.set(scenario);
                String expected = switch (scenario) {
                    case "detail_changed", "etag" -> "object_changed";
                    case "length" -> "response_too_large";
                    case "hash" -> "package_hash_mismatch";
                    case "metadata" -> "release_metadata_mismatch";
                    default -> "invalid_response";
                };
                var failure = assertThrows(CommunityPackages.Failure.class, () -> client.download(f.descriptor, ENV, new CommunitySearchHttpClient.Cancellation()));
                assertEquals(expected, failure.code(), scenario);
            }
        }
    }

    @Test void malformedDetailsAndDifferentSelectionAreRejected() throws Exception {
        var f = fixture("rel_a", "alice"); var index = index(f); byte[] original = index.releaseDetails(f.descriptor.releaseRef());
        for (String scenario : List.of("unknown", "coercion", "permission")) {
            var root = json(original); var manifest = root.getAsJsonObject("immutableRelease").getAsJsonObject("sourceManifest");
            if (scenario.equals("unknown")) manifest.addProperty("script", "run");
            else if (scenario.equals("coercion")) manifest.addProperty("author", 42);
            else manifest.addProperty("trainingPermission", "FREE");
            failure("invalid_response", () -> CommunityReleaseDetails.decode(bytes(root), f.descriptor));
        }
        var other = fixture("rel_b", "bob"); failure("object_changed", () -> CommunityReleaseDetails.decode(original, other.descriptor));
    }

    @Test void persistsOriginalBytesAndRevalidatesWithOnlyRelevantEnvironmentEvidence() throws Exception {
        var f = fixture("rel_a", "alice"); var fetched = fetched(index(f), f); Path cache = temp.resolve("cache");
        String id = CommunityDownloadCache.save(cache, fetched, ENV, CLOCK);
        var cached = CommunityDownloadCache.read(cache, id, ENV);
        assertArrayEquals(f.bytes, cached.download().originalBytes()); assertEquals(CLOCK.instant(), cached.verifiedAt());
        assertEquals("alice", cached.details().manifest().namespace()); assertEquals(id, CommunityDownloadCache.save(cache, fetched, ENV, CLOCK));
        String metadata = Files.readString(cache.resolve("receipts").resolve(id).resolve("receipt.json"));
        assertFalse(metadata.contains("unrelated")); assertFalse(metadata.contains(cache.toString()));
        failure("environment_mismatch", () -> CommunityDownloadCache.read(cache, id, new ComponentPackageIO.Environment("1.21.10", Map.of("decor_test", "2"))));
        failure("environment_mismatch", () -> CommunityDownloadCache.read(cache, id, new ComponentPackageIO.Environment("other", Map.of("decor_test", "1"))));
    }

    @Test void sameRawPackageSharesObjectButNeverMergesReleaseReceipts() throws Exception {
        var f = fixture("rel_a", "alice"); var ref = f.descriptor.releaseRef();
        var second = new Fixture(f.bytes, new CommunityPackages.Descriptor(new CommunityPackages.ReleaseRef(ref.authorityId(), ref.resourceId(), "rel_b", ref.packageSha256(), ref.payloadSha256(), ref.manifestSha256()), f.bytes.length, ComponentPackageIO.SCHEMA, f.descriptor.fingerprints()));
        var index = index(f, second); Path cache = temp.resolve("cache");
        String a = CommunityDownloadCache.save(cache, fetched(index, f), ENV, CLOCK), b = CommunityDownloadCache.save(cache, fetched(index, second), ENV, CLOCK);
        assertNotEquals(a, b); try (var files = Files.list(cache.resolve("objects"))) { assertEquals(1, files.count()); }
        assertEquals("rel_b", CommunityDownloadCache.read(cache, b, ENV).download().descriptor().releaseRef().releaseId());
    }

    @Test void corruptRawObjectIsNeitherAcceptedNorOverwritten() throws Exception {
        var f = fixture("rel_a", "alice"); var fetched = fetched(index(f), f); Path cache = temp.resolve("cache");
        String id = CommunityDownloadCache.save(cache, fetched, ENV, CLOCK);
        Path raw = cache.resolve("objects").resolve(f.descriptor.releaseRef().packageSha256()).resolve("original.fccomponent.json");
        Files.writeString(raw, "corrupt"); failure("cache_corrupt", () -> CommunityDownloadCache.read(cache, id, ENV));
        failure("cache_corrupt", () -> CommunityDownloadCache.save(cache, fetched, ENV, CLOCK)); assertEquals("corrupt", Files.readString(raw));
    }

    @Test void receiptTamperingAndMixedSelectedSourcesFailBeforeCommit() throws Exception {
        var a = fixture("rel_a", "alice"); var b = fixture("rel_b", "bob"); var index = index(a, b); Path cache = temp.resolve("cache");
        var fa = fetched(index, a); var fb = fetched(index, b);
        failure("release_metadata_mismatch", () -> CommunityDownloadCache.save(cache, new CommunitySearchHttpClient.Fetched(fb.details(), fa.download()), ENV, CLOCK));
        assertFalse(Files.exists(cache)); String id = CommunityDownloadCache.save(cache, fa, ENV, CLOCK);
        Path receipt = cache.resolve("receipts").resolve(id).resolve("receipt.json"); var root = json(Files.readAllBytes(receipt)); root.addProperty("id", "receipt_" + "0".repeat(64)); Files.write(receipt, bytes(root));
        failure("cache_corrupt", () -> CommunityDownloadCache.read(cache, id, ENV));
        failure("invalid_receipt_id", () -> CommunityDownloadCache.read(cache, "../outside", ENV));
    }

    @Test void failedEnvironmentAndCapacityNeverCreateValidReceipt() throws Exception {
        var f = fixture("rel_a", "alice"); var fetched = fetched(index(f), f); Path cache = temp.resolve("cache");
        failure("environment_mismatch", () -> CommunityDownloadCache.save(cache, fetched, new ComponentPackageIO.Environment("other", Map.of()), CLOCK));
        assertFalse(Files.exists(cache)); Files.createDirectories(cache.resolve("objects"));
        for (int i = 0; i < CommunityDownloadCache.MAX_OBJECTS; i++) Files.createDirectory(cache.resolve("objects/.pending-" + i));
        failure("cache_capacity", () -> CommunityDownloadCache.save(cache, fetched, ENV, CLOCK));
        try (var files = Files.list(cache.resolve("receipts"))) { assertEquals(0, files.count()); }
    }

    @Test void oneCancellationCoversDetailsAndInFlightPackageBody() throws Exception {
        var f = fixture("rel_a", "alice"); var executor = Executors.newSingleThreadExecutor();
        try (var server = new Server(index(f), f); var client = new CommunitySearchHttpClient("test", server.base())) {
            server.mode.set("cancel"); var cancellation = new CommunitySearchHttpClient.Cancellation();
            var result = executor.submit(() -> { try { client.download(f.descriptor, ENV, cancellation); return "unexpected"; } catch (CommunityPackages.Failure failed) { return failed.code(); } });
            assertTrue(server.started.await(5, TimeUnit.SECONDS)); cancellation.cancel(); server.release.countDown();
            assertEquals("cancelled", result.get(5, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
}
