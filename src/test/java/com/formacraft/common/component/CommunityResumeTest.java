package com.formacraft.common.component;

import com.formacraft.common.component.community.*;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CommunityResumeTest {
    @TempDir Path temp;
    private static final ComponentPackageIO.Environment ENV = new ComponentPackageIO.Environment("1.21.10",Map.of());
    private record Fixture(byte[] bytes, CommunityPackages.Descriptor descriptor) {}
    private static Fixture fixture() throws Exception {
        var def = new ComponentDefinition(); def.id = "column"; def.name = "Column"; def.category = ComponentCategory.COLUMN; def.sourceOrigin = "CAPTURED";
        def.size = new ComponentDefinition.Size(); def.size.w = def.size.d = def.size.h = 1;
        def.anchor = new ComponentDefinition.Anchor(); def.anchor.facing = "SOUTH";
        var block = new ComponentDefinition.BlockEntry(); block.block = "minecraft:stone"; def.blocks = List.of(block);
        var manifest = new ComponentPackageIO.Manifest("alice","1","Alice","LicenseRef-Test",ComponentPackageIO.TrainingPermission.DENIED,"1.21.10",Map.of(),Map.of(),List.of());
        var pack = ComponentPackageIO.create(def,manifest,ENV);
        byte[] raw = (new String(pack.toBytes(),StandardCharsets.UTF_8)+" ".repeat(CommunityResumeStore.CHUNK_BYTES*2+37)).getBytes(StandardCharsets.UTF_8);
        var ref = new CommunityPackages.ReleaseRef("test","res","rel_a",CommunityPackages.sha256(raw),pack.sha256(),pack.manifestSha256());
        return new Fixture(raw,new CommunityPackages.Descriptor(ref,raw.length,ComponentPackageIO.SCHEMA,ComponentContentIdentity.fingerprints(def)));
    }
    private static Fixture otherRelease(Fixture f) {
        var ref = f.descriptor.releaseRef();
        var other = new CommunityPackages.ReleaseRef(ref.authorityId(),ref.resourceId(),"rel_b",ref.packageSha256(),ref.payloadSha256(),ref.manifestSha256());
        return new Fixture(f.bytes,new CommunityPackages.Descriptor(other,f.bytes.length,ComponentPackageIO.SCHEMA,f.descriptor.fingerprints()));
    }
    private static void failure(String code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code,assertThrows(CommunityPackages.Failure.class,operation).code());
    }
    private Path staging() { return temp.resolve("staging"); }
    private static final class Server implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final ExecutorService executor = Executors.newCachedThreadPool();
        final OfflineCommunityIndex index = new OfflineCommunityIndex("test",Clock.systemUTC());
        final AtomicReference<String> mode = new AtomicReference<>("normal");
        final List<String> requests = new CopyOnWriteArrayList<>();
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        Server(Fixture... fixtures) throws Exception {
            Map<String,Fixture> releases = new HashMap<>();
            for (var f : fixtures) { releases.put(f.descriptor.releaseRef().releaseId(),f); index.add(f.descriptor,f.bytes); }
            server.createContext("/api/v1/",exchange -> {
                try {
                    String path = exchange.getRequestURI().getPath();
                    if (path.startsWith("/api/v1/releases/")) {
                        var f = releases.get(path.substring("/api/v1/releases/".length()));
                        reply(exchange,200,index.releaseDetails(f.descriptor.releaseRef())); return;
                    }
                    String id = exchange.getRequestURI().getQuery().substring("releaseId=".length()); var f = releases.get(id);
                    byte[] raw = index.packageBytes(f.descriptor.releaseRef()); // Recheck availability for every range.
                    assertEquals("/api/v1/package-blobs/"+f.descriptor.releaseRef().packageSha256(),path);
                    String range = exchange.getRequestHeaders().getFirst("Range");
                    assertNotNull(range); requests.add(id+":"+range);
                    String etag = "\""+f.descriptor.releaseRef().packageSha256()+"\"";
                    assertEquals(etag,exchange.getRequestHeaders().getFirst("If-Range"));
                    var bounds = range.substring("bytes=".length()).split("-"); int start = Integer.parseInt(bounds[0]), end = Integer.parseInt(bounds[1]);
                    String scenario = mode.get();
                    if (scenario.equals("fail_second") && start == CommunityResumeStore.CHUNK_BYTES) { reply(exchange,503,"{}".getBytes(StandardCharsets.UTF_8)); return; }
                    if (scenario.equals("416")) { reply(exchange,416,"{}".getBytes(StandardCharsets.UTF_8)); return; }
                    byte[] body = Arrays.copyOfRange(raw,start,end+1);
                    exchange.getResponseHeaders().set("ETag",scenario.equals("etag") ? "\""+"0".repeat(64)+"\"" : scenario.equals("weak") ? "W/"+etag : etag);
                    String contentRange = "bytes "+(scenario.equals("start") ? start+1 : start)+"-"+end+"/"+(raw.length+(scenario.equals("total") ? 1 : 0));
                    exchange.getResponseHeaders().set("Content-Range",contentRange);
                    if (scenario.equals("missing_range")) exchange.getResponseHeaders().remove("Content-Range");
                    if (scenario.equals("extra")) body = Arrays.copyOf(body,body.length+1);
                    if (scenario.equals("corrupt")) { body = body.clone(); body[0] = '!'; }
                    if (scenario.equals("withdraw")) index.setAvailable(id,false);
                    if (scenario.equals("cancel_second") && start == CommunityResumeStore.CHUNK_BYTES) {
                        exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(206,body.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(body[0]); output.flush(); started.countDown();
                            try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                            output.write(body,1,body.length-1);
                        }
                    } else reply(exchange,scenario.equals("ignored") ? 200 : 206,scenario.equals("ignored") ? raw : body);
                } catch (CommunityPackages.Failure failed) {
                    var error = new JsonObject(); error.addProperty("requestId",exchange.getRequestHeaders().getFirst("X-Formacraft-Request-Id"));
                    error.addProperty("code",failed.code()); error.addProperty("message","Fixture unavailable"); error.addProperty("retryable",false);
                    reply(exchange,404,error.toString().getBytes(StandardCharsets.UTF_8));
                } catch (IOException aborted) { exchange.close(); }
            }); server.setExecutor(executor); server.start();
        }
        URI base() { return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/"); }
        public void close() { release.countDown(); server.stop(0); executor.shutdownNow(); }
        static void reply(HttpExchange exchange,int status,byte[] bytes) throws IOException {
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(status,bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        }
    }
    private static CommunitySearchHttpClient.Cancellation token() { return new CommunitySearchHttpClient.Cancellation(); }
    private void partial(Server server,Fixture f) throws Exception {
        server.mode.set("fail_second");
        try (var client = new CommunitySearchHttpClient("test",server.base())) {
            failure("service_unavailable",() -> client.downloadResumable(f.descriptor,ENV,staging(),token()));
        }
        assertEquals(CommunityResumeStore.CHUNK_BYTES,CommunityResumeStore.inspect(staging(),f.descriptor).completedBytes());
    }

    @Test void newClientResumesCompletedBlockAndReturnsExactOriginalWithoutPublishingReceipt() throws Exception {
        var f = fixture(); assertEquals(0,CommunityResumeStore.inspect(staging(),f.descriptor).completedBytes()); assertFalse(Files.exists(staging()));
        try (var server = new Server(f)) {
            partial(server,f); server.requests.clear(); server.mode.set("normal");
            try (var client = new CommunitySearchHttpClient("test",server.base())) {
                var fetched = client.downloadResumable(f.descriptor,ENV,staging(),token()); assertArrayEquals(f.bytes,fetched.download().originalBytes());
                fetched.details().verify(fetched.download()); assertEquals(ComponentPackageIO.TrainingPermission.DENIED,fetched.details().manifest().trainingPermission());
                assertTrue(server.requests.getFirst().startsWith("rel_a:bytes="+CommunityResumeStore.CHUNK_BYTES+"-"));
                assertEquals(2,server.requests.size());
            }
            assertEquals(0,CommunityResumeStore.inspect(staging(),f.descriptor).completedBytes());
            try (var files = Files.list(staging())) { assertEquals(0,files.count()); }
            assertFalse(Files.exists(temp.resolve("receipts")));
        }
    }

    @Test void ignoredRangesIncorrectIntervalsEtagsAndOverlongBodiesNeverBecomeChunks() throws Exception {
        for (String scenario : List.of("ignored","start","total","etag","weak","extra","missing_range","416")) {
            var f = fixture();
            try (var server = new Server(f); var client = new CommunitySearchHttpClient("test",server.base())) {
                server.mode.set(scenario);
                String expected = switch (scenario) { case "ignored" -> "range_not_supported"; case "etag","weak" -> "object_changed"; case "extra" -> "response_too_large"; default -> "invalid_range"; };
                var failed = assertThrows(CommunityPackages.Failure.class,() -> client.downloadResumable(f.descriptor,ENV,staging(),token()));
                assertEquals(expected,failed.code(),scenario); assertEquals(0,CommunityResumeStore.inspect(staging(),f.descriptor).completedBytes());
            }
        }
    }

    @Test void finalWholeHashMismatchDiscardsEvenIndividuallyCompleteChunks() throws Exception {
        var f = fixture();
        try (var server = new Server(f); var client = new CommunitySearchHttpClient("test",server.base())) {
            server.mode.set("corrupt"); failure("package_hash_mismatch",() -> client.downloadResumable(f.descriptor,ENV,staging(),token()));
            assertEquals(3,server.requests.size()); assertEquals(0,CommunityResumeStore.inspect(staging(),f.descriptor).completedBytes());
        }
    }

    @Test void fullyReceivedBytesRemainUninstallableUntilTheTargetEnvironmentPasses() throws Exception {
        var f = fixture();
        try (var server = new Server(f); var client = new CommunitySearchHttpClient("test",server.base())) {
            var wrong = new ComponentPackageIO.Environment("other",Map.of());
            failure("environment_mismatch",() -> client.downloadResumable(f.descriptor,wrong,staging(),token()));
            assertEquals(f.bytes.length,CommunityResumeStore.inspect(staging(),f.descriptor).completedBytes());
            int before = server.requests.size();
            assertArrayEquals(f.bytes,client.downloadResumable(f.descriptor,ENV,staging(),token()).download().originalBytes());
            assertEquals(before,server.requests.size()); assertEquals(0,CommunityResumeStore.inspect(staging(),f.descriptor).completedBytes());
        }
    }

    @Test void cancellationRetainsOnlyFirstCompleteBlockAndRejectsConcurrentWriter() throws Exception {
        var f = fixture(); var executor = Executors.newSingleThreadExecutor();
        try (var server = new Server(f); var client = new CommunitySearchHttpClient("test",server.base())) {
            server.mode.set("cancel_second"); var cancellation = token();
            var result = executor.submit(() -> { try { client.downloadResumable(f.descriptor,ENV,staging(),cancellation); return "unexpected"; } catch (CommunityPackages.Failure failed) { return failed.code(); } });
            assertTrue(server.started.await(5,TimeUnit.SECONDS));
            try (var other = new CommunitySearchHttpClient("test",server.base())) { failure("download_busy",() -> other.downloadResumable(f.descriptor,ENV,staging(),token())); }
            cancellation.cancel(); server.release.countDown(); assertEquals("cancelled",result.get(5,TimeUnit.SECONDS));
            assertEquals(CommunityResumeStore.CHUNK_BYTES,CommunityResumeStore.inspect(staging(),f.descriptor).completedBytes());
            server.mode.set("normal"); server.requests.clear(); assertArrayEquals(f.bytes,client.downloadResumable(f.descriptor,ENV,staging(),token()).download().originalBytes());
            assertTrue(server.requests.getFirst().startsWith("rel_a:bytes="+CommunityResumeStore.CHUNK_BYTES+"-"));
        } finally { executor.shutdownNow(); }
    }

    @Test void newInvocationRechecksWithdrawalAndNeverUsesStalePartialBytes() throws Exception {
        var f = fixture();
        try (var server = new Server(f)) {
            partial(server,f); int before = server.requests.size(); server.index.setAvailable("rel_a",false);
            try (var client = new CommunitySearchHttpClient("test",server.base())) { failure("release_unavailable",() -> client.downloadResumable(f.descriptor,ENV,staging(),token())); }
            assertEquals(before,server.requests.size()); assertEquals(0,CommunityResumeStore.inspect(staging(),f.descriptor).completedBytes());
        }
    }

    @Test void eachRangeAlsoRechecksDeliveryAfterTheFirstChunk() throws Exception {
        var f = fixture();
        try (var server = new Server(f); var client = new CommunitySearchHttpClient("test",server.base())) {
            server.mode.set("withdraw"); failure("release_unavailable",() -> client.downloadResumable(f.descriptor,ENV,staging(),token()));
            assertEquals(1,server.requests.size()); assertEquals(0,CommunityResumeStore.inspect(staging(),f.descriptor).completedBytes());
        }
    }

    @Test void localCorruptionIsDetectedBeforeAnyAdditionalRangeAndSafeStagingIsDiscarded() throws Exception {
        var f = fixture();
        try (var server = new Server(f)) {
            partial(server,f); int before = server.requests.size();
            Path part; try (var files = Files.walk(staging())) { part = files.filter(path -> path.getFileName().toString().endsWith(".part")).findFirst().orElseThrow(); }
            Files.writeString(part,"corrupt");
            try (var client = new CommunitySearchHttpClient("test",server.base())) { failure("invalid_checkpoint",() -> client.downloadResumable(f.descriptor,ENV,staging(),token())); }
            assertEquals(before,server.requests.size()); assertEquals(0,CommunityResumeStore.inspect(staging(),f.descriptor).completedBytes());
        }
    }

    @Test void samePackageInAnotherReleaseCannotReuseTheOtherReleasePrefix() throws Exception {
        var f = fixture(); var other = otherRelease(f);
        try (var server = new Server(f,other)) {
            partial(server,f); server.mode.set("normal"); server.requests.clear();
            try (var client = new CommunitySearchHttpClient("test",server.base())) { assertArrayEquals(f.bytes,client.downloadResumable(other.descriptor,ENV,staging(),token()).download().originalBytes()); }
            assertTrue(server.requests.getFirst().startsWith("rel_b:bytes=0-"));
            assertEquals(CommunityResumeStore.CHUNK_BYTES,CommunityResumeStore.inspect(staging(),f.descriptor).completedBytes());
            CommunityResumeStore.discard(staging(),f.descriptor); assertEquals(0,CommunityResumeStore.inspect(staging(),f.descriptor).completedBytes());
        }
    }

    @Test void capacityAndUnknownFilesAreBoundedAndNeverRecursivelyDeleted() throws Exception {
        var f = fixture(); Files.createDirectories(staging());
        for (int i = 0; i < CommunityResumeStore.MAX_TRANSFERS; i++) Files.createDirectory(staging().resolve("resume_"+String.format(Locale.ROOT,"%064x",i)));
        try (var server = new Server(f); var client = new CommunitySearchHttpClient("test",server.base())) {
            failure("resume_capacity",() -> client.downloadResumable(f.descriptor,ENV,staging(),token())); assertTrue(server.requests.isEmpty());
        }
        // Test a different root so quota fixtures remain untouched.
        Path otherRoot = temp.resolve("other");
        try (var server = new Server(f); var client = new CommunitySearchHttpClient("test",server.base())) {
            server.mode.set("fail_second"); failure("service_unavailable",() -> client.downloadResumable(f.descriptor,ENV,otherRoot,token()));
            Path transfer; try (var entries = Files.list(otherRoot)) { transfer = entries.findFirst().orElseThrow(); }
            Path unknown = Files.createDirectory(transfer.resolve("unknown")); Path marker = unknown.resolve("keep.txt"); Files.writeString(marker,"keep");
            server.mode.set("normal"); failure("invalid_checkpoint",() -> client.downloadResumable(f.descriptor,ENV,otherRoot,token()));
            assertEquals("keep",Files.readString(marker)); assertThrows(IOException.class,() -> CommunityResumeStore.discard(otherRoot,f.descriptor));
        }
    }
}
