package com.formacraft.common.component;

import com.formacraft.common.component.community.*;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CommunityTransportTest {
    private static final Clock CLOCK = Clock.systemUTC();
    private static byte[] request(String fields, int pageSize, String cursor) {
        var root = JsonParser.parseString("""
                {"contract":"formacraft.community.search.v1","requestId":"request","needId":"column","query":{}}
                """).getAsJsonObject();
        root.add("fields", JsonParser.parseString(fields)); root.addProperty("pageSize", pageSize); root.addProperty("cursor", cursor);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] request() { return request("[\"identity\",\"package\",\"type\",\"rights\",\"evidence\"]", 10, null); }
    private static OfflineCommunityIndex index(int count, String license, String title) throws Exception {
        var index = new OfflineCommunityIndex("test", CLOCK);
        for (int i = 0; i < count; i++) {
            var def = new ComponentDefinition(); def.id = "column"; def.name = title; def.category = ComponentCategory.COLUMN; def.sourceOrigin = "CAPTURED";
            def.size = new ComponentDefinition.Size(); def.size.w = def.size.d = 1; def.size.h = i + 1;
            def.anchor = new ComponentDefinition.Anchor(); def.anchor.facing = "SOUTH";
            var block = new ComponentDefinition.BlockEntry(); block.block = "minecraft:stone"; def.blocks = List.of(block);
            var manifest = new ComponentPackageIO.Manifest("source", "1", "Author\nAttribution", license,
                    ComponentPackageIO.TrainingPermission.DENIED, "1.21.10", Map.of(), Map.of(), List.of());
            var pack = ComponentPackageIO.create(def, manifest, new ComponentPackageIO.Environment("1.21.10", Map.of())); byte[] bytes = pack.toBytes();
            var ref = new CommunityPackages.ReleaseRef("test", "res", "rel_" + i, CommunityPackages.sha256(bytes), pack.sha256(), pack.manifestSha256());
            index.add(new CommunityPackages.Descriptor(ref, bytes.length, ComponentPackageIO.SCHEMA, ComponentContentIdentity.fingerprints(def)), bytes);
        }
        return index;
    }
    private static void failure(String code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(CommunityPackages.Failure.class, operation).code());
    }
    private static byte[] bytes(JsonObject root) { return root.toString().getBytes(StandardCharsets.UTF_8); }
    private static void reply(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    private static final class Server implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService executor = Executors.newCachedThreadPool();
        Server(HttpHandler handler) throws IOException { server.createContext("/api/v1/search", handler); server.setExecutor(executor); server.start(); }
        URI base() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"); }
        public void close() { server.stop(0); executor.shutdownNow(); }
    }

    @Test void projectsOnlyRequestedFieldsAndPreservesUnprovenAndRightsMetadata() throws Exception {
        var index = index(1, "LicenseRef-Test\nSecond line", "😀".repeat(300));
        var request = CommunitySearchContract.decode(request());
        byte[] result = CommunitySearchResponse.search(index, request, CLOCK);
        var decoded = CommunitySearchResponse.decode(result, request, "test");
        var root = decoded.json(); var candidate = root.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("preferredCandidate");
        assertTrue(candidate.get("platformPublisher").isJsonNull()); assertEquals("unknown", candidate.getAsJsonObject("fit").get("actualFit").getAsString());
        assertEquals("DENIED", candidate.getAsJsonObject("rights").get("trainingPermission").getAsString());
        assertEquals("LicenseRef-Test\nSecond line", candidate.getAsJsonObject("rights").get("license").getAsString());
        assertEquals(256, candidate.get("title").getAsString().codePointCount(0, candidate.get("title").getAsString().length()));
        assertEquals(1, root.getAsJsonArray("truncatedFields").size());
        root.addProperty("requestId", "changed"); assertEquals("request", decoded.json().get("requestId").getAsString());
        assertFalse(new String(result, StandardCharsets.UTF_8).contains("componentJson")); assertFalse(candidate.has("blocks"));
        var minimal = CommunitySearchContract.decode(request("[\"identity\"]", 10, null));
        var summary = CommunitySearchResponse.decode(CommunitySearchResponse.search(index, minimal, CLOCK), minimal, "test").json();
        var item = summary.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("preferredCandidate");
        assertFalse(item.has("rights")); assertFalse(item.has("type")); assertTrue(item.has("releaseRef")); assertTrue(item.has("package"));
    }

    @Test void budgetSplitsPagesWithoutLosingGroupsAndKeepsSnapshot() throws Exception {
        var index = index(20, "\u0001".repeat(2047) + "x", "Column");
        Set<String> seen = new HashSet<>(); String cursor = null, snapshot = null; int pages = 0;
        do {
            var request = CommunitySearchContract.decode(request("[\"identity\",\"rights\"]", 20, cursor));
            byte[] encoded = CommunitySearchResponse.search(index, request, CLOCK); assertTrue(encoded.length <= CommunitySearchResponse.MAX_BYTES);
            var decoded = CommunitySearchResponse.decode(encoded, request, "test"); var root = decoded.json();
            String current = root.getAsJsonObject("indexSnapshot").get("id").getAsString();
            if (snapshot == null) snapshot = current; else assertEquals(snapshot, current);
            for (var descriptor : decoded.candidates()) assertTrue(seen.add(descriptor.releaseRef().releaseId()));
            cursor = root.get("nextCursor").isJsonNull() ? null : root.get("nextCursor").getAsString(); pages++;
            assertTrue(pages < 5);
        } while (cursor != null);
        assertEquals(20, seen.size()); assertTrue(pages > 1);
    }

    @Test void rejectsMismatchedIdentityFabricatedEvidenceAndUnexpectedPayload() throws Exception {
        var request = CommunitySearchContract.decode(request());
        byte[] original = CommunitySearchResponse.search(index(1, "LicenseRef-Test", "Column"), request, CLOCK);
        for (String key : List.of("requestId", "needId", "queryFingerprint", "profile", "rankingPolicy")) {
            var root = JsonParser.parseString(new String(original, StandardCharsets.UTF_8)).getAsJsonObject(); root.addProperty(key, "wrong");
            failure("invalid_response", () -> CommunitySearchResponse.decode(bytes(root), request, "test"));
        }
        for (String change : List.of("publisher", "hash", "fit", "blocks", "duplicate", "grant")) {
            var root = JsonParser.parseString(new String(original, StandardCharsets.UTF_8)).getAsJsonObject();
            var candidate = root.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("preferredCandidate");
            switch (change) {
                case "publisher" -> candidate.add("platformPublisher", new JsonObject());
                case "hash" -> candidate.getAsJsonObject("package").addProperty("packageSha256", "0".repeat(64));
                case "fit" -> candidate.getAsJsonObject("fit").addProperty("actualFit", "passed");
                case "blocks" -> candidate.add("blocks", new JsonArray());
                case "duplicate" -> root.getAsJsonArray("items").add(root.getAsJsonArray("items").get(0).deepCopy());
                case "grant" -> candidate.getAsJsonObject("rights").addProperty("trainingPermission", "FREE");
            }
            failure("invalid_response", () -> CommunitySearchResponse.decode(bytes(root), request, "test"));
        }
        failure("invalid_response", () -> CommunitySearchResponse.decode(original, request, "other"));
    }

    @Test void realHttpSearchUsesFixedPathAndRoundTripsBoundedResponse() throws Exception {
        var index = index(1, "LicenseRef-Test", "Column");
        try (var server = new Server(exchange -> {
            assertEquals("POST", exchange.getRequestMethod()); assertEquals("/api/v1/search", exchange.getRequestURI().getPath());
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            try { reply(exchange, 200, CommunitySearchResponse.search(index, CommunitySearchContract.decode(exchange.getRequestBody().readNBytes(32769)), CLOCK)); }
            catch (IOException invalid) { throw invalid; }
        }); var client = new CommunitySearchHttpClient("test", server.base())) {
            assertEquals(1, client.search(request(), new CommunitySearchHttpClient.Cancellation()).candidates().size());
        }
    }

    @Test void rejectsRedirectWithoutContactingDestination() throws Exception {
        var calls = new AtomicInteger();
        try (var server = new Server(exchange -> {
            exchange.getResponseHeaders().set("Location", "/other"); exchange.sendResponseHeaders(302, -1); exchange.close();
        })) {
            server.server.createContext("/other", exchange -> { calls.incrementAndGet(); reply(exchange, 200, new byte[0]); });
            try (var client = new CommunitySearchHttpClient("test", server.base())) {
                failure("redirect_rejected", () -> client.search(request(), new CommunitySearchHttpClient.Cancellation()));
                assertEquals(0, calls.get());
            }
        }
    }

    @Test void boundsChunkedBodyAndRejectsClaimedOversizeOrEncoding() throws Exception {
        for (String scenario : List.of("chunked", "length", "encoding", "type")) {
            try (var server = new Server(exchange -> {
                exchange.getResponseHeaders().set("Content-Type", scenario.equals("type") ? "text/html" : "application/json");
                if (scenario.equals("encoding")) exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(200, scenario.equals("length") ? CommunitySearchResponse.MAX_BYTES + 1 : 0);
                try (var out = exchange.getResponseBody()) {
                    if (scenario.equals("chunked")) out.write(new byte[CommunitySearchResponse.MAX_BYTES + 1]);
                    else { out.write('x'); out.flush(); } // Fixed-length headers must actually leave the test server.
                }
                catch (IOException disconnected) { /* Client deliberately aborts rejected responses. */ }
            }); var client = new CommunitySearchHttpClient("test", server.base())) {
                String expected = scenario.equals("encoding") ? "unsupported_encoding" : scenario.equals("type") ? "invalid_response" : "response_too_large";
                var failed = assertThrows(CommunityPackages.Failure.class, () -> client.search(request(), new CommunitySearchHttpClient.Cancellation()));
                assertEquals(expected, failed.code(), scenario);
            }
        }
    }

    @Test void cancellationStopsInFlightBodyAndCannotBeReused() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var executor = Executors.newSingleThreadExecutor();
        try (var server = new Server(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); started.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        }); var client = new CommunitySearchHttpClient("test", server.base())) {
            var cancellation = new CommunitySearchHttpClient.Cancellation();
            var result = executor.submit(() -> { try { client.search(request(), cancellation); return "unexpected_success"; } catch (CommunityPackages.Failure failed) { return failed.code(); } });
            assertTrue(started.await(5, TimeUnit.SECONDS)); cancellation.cancel(); release.countDown();
            assertEquals("cancelled", result.get(5, TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class, () -> client.search(request(), cancellation));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void preCancelledRequestMakesNoConnectionAndUntrustedBasesAreRejected() throws Exception {
        for (String base : List.of("http://example.com/", "https://user:secret@example.com/", "https://example.com/?url=x", "https://example.com/a/../b/", "https://example.com/%2e/", "https://example.com//other-host/"))
            assertThrows(IllegalArgumentException.class, () -> new CommunitySearchHttpClient("test", URI.create(base)));
        try (var client = new CommunitySearchHttpClient("test", URI.create("http://127.0.0.1:1/"))) {
            var cancellation = new CommunitySearchHttpClient.Cancellation(); cancellation.cancel();
            failure("cancelled", () -> client.search(request(), cancellation));
        }
    }

    @Test void handlesBoundedServiceErrorsWithoutExposingRemoteMessage() throws Exception {
        for (int status : List.of(400, 429, 503)) {
            try (var server = new Server(exchange -> reply(exchange, status,
                    "{\"requestId\":\"request\",\"code\":\"cursor_expired\",\"message\":\"private/path\",\"retryable\":false}".getBytes(StandardCharsets.UTF_8)));
                 var client = new CommunitySearchHttpClient("test", server.base())) {
                String expected = status == 400 ? "cursor_expired" : status == 429 ? "rate_limited" : "service_unavailable";
                var failure = assertThrows(CommunityPackages.Failure.class, () -> client.search(request(), new CommunitySearchHttpClient.Cancellation()));
                assertEquals(expected, failure.code()); assertFalse(failure.getMessage().contains("private"));
            }
        }
    }
}
