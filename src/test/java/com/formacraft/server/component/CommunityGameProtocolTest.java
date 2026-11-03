package com.formacraft.server.component;

import com.formacraft.client.component.CommunityTaskClientState;
import com.formacraft.common.component.*;
import com.formacraft.common.component.community.*;
import com.formacraft.common.network.CommunityTaskNetworking;
import com.formacraft.common.network.CommunityTaskNetworking.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class CommunityGameProtocolTest {
    @TempDir Path temp;
    private static final ComponentPackageIO.Environment ENV = new ComponentPackageIO.Environment("1.21.10",Map.of());
    private static final class Time extends Clock {
        Instant now = Instant.parse("2026-11-03T00:00:00Z"); final AtomicLong nanos = new AtomicLong();
        public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId zone) { return this; } public Instant instant() { return now; }
        void advance(long millis) { now = now.plusMillis(millis); nanos.addAndGet(millis*1_000_000); }
    }
    private static final class Backend implements CommunityTaskCoordinator.Backend {
        final OfflineCommunityIndex index; final Time time; final Map<String,CommunityPackages.Descriptor> candidates = new HashMap<>();
        int calls;
        Backend(Time time) throws Exception {
            this.time = time; index = new OfflineCommunityIndex("test",time);
            // Long independent licensing declarations force real multi-chunk JSON responses.
            for (int i = 0; i < 7; i++) {
                var d = new ComponentDefinition(); d.id = "column"; d.name = "Column "+i; d.category = ComponentCategory.COLUMN; d.sourceOrigin = "CAPTURED";
                d.size = new ComponentDefinition.Size(); d.size.w = d.size.d = 1; d.size.h = i+1;
                d.anchor = new ComponentDefinition.Anchor(); d.anchor.facing = "SOUTH";
                var block = new ComponentDefinition.BlockEntry(); block.block = "minecraft:stone"; d.blocks = List.of(block);
                var m = new ComponentPackageIO.Manifest("author","1","Author","Terms "+"x".repeat(2000),ComponentPackageIO.TrainingPermission.DENIED,"1.21.10",Map.of(),Map.of(),List.of());
                var pack = ComponentPackageIO.create(d,m,ENV); byte[] bytes = pack.toBytes();
                var ref = new CommunityPackages.ReleaseRef("test","res","rel_"+i,CommunityPackages.sha256(bytes),pack.sha256(),pack.manifestSha256());
                var descriptor = new CommunityPackages.Descriptor(ref,bytes.length,ComponentPackageIO.SCHEMA,ComponentContentIdentity.fingerprints(d));
                index.add(descriptor,bytes); candidates.put(ref.releaseId(),descriptor);
            }
        }
        public CommunitySearchResponse.Decoded search(byte[] bytes,CommunitySearchHttpClient.Cancellation cancellation) throws IOException {
            calls++; var request = CommunitySearchContract.decode(bytes);
            return CommunitySearchResponse.decode(CommunitySearchResponse.search(index,request,time),request,"test");
        }
        public CommunitySearchHttpClient.Fetched download(CommunityPackages.Descriptor selected,ComponentPackageIO.Environment environment,Path staging,
                CommunityTaskCoordinator.Transfer transfer,CommunitySearchHttpClient.Cancellation cancellation) throws IOException {
            return new CommunitySearchHttpClient.Fetched(CommunityReleaseDetails.decode(index.releaseDetails(selected.releaseRef()),selected),index.download(selected.releaseRef(),environment));
        }
        public void shutdownNow() {}
    }
    private CommunityTaskCoordinator tasks(Time time,Backend backend) throws IOException { return new CommunityTaskCoordinator(Files.createDirectories(temp.resolve("world")),"test",ENV,time,backend); }
    private static byte[] search() {
        var root = new JsonObject(); root.addProperty("contract",CommunitySearchContract.CONTRACT); root.addProperty("requestId","game"); root.addProperty("needId","column");
        root.add("query",new JsonObject()); return root.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static Request request(Operation operation,UUID session,UUID subject,int offset,int length,byte[] data) {
        return new Request(UUID.randomUUID(),operation,session,subject,"",offset,length,data,false);
    }
    private static UUID open(CommunityTaskBridge bridge,UUID owner) {
        var response = bridge.handle(owner,request(Operation.OPEN,null,null,0,0,null)); assertEquals(Reply.OPENED,response.reply()); return response.sessionId();
    }
    private static UUID submit(CommunityTaskBridge bridge,UUID owner,UUID session,byte[] json) {
        UUID upload = UUID.randomUUID(); assertEquals(Reply.ACK,bridge.handle(owner,request(Operation.SEARCH_BEGIN,session,upload,0,json.length,null)).reply());
        for (int offset = 0; offset < json.length; offset += CommunityTaskNetworking.CHUNK_BYTES)
            assertEquals(Reply.ACK,bridge.handle(owner,request(Operation.SEARCH_CHUNK,session,upload,offset,0,Arrays.copyOfRange(json,offset,Math.min(json.length,offset+8192)))).reply());
        var response = bridge.handle(owner,request(Operation.SEARCH_SUBMIT,session,upload,0,0,null)); assertEquals(Reply.STATUS,response.reply()); return response.subjectId();
    }
    private static void pump(CommunityTaskClientState client,CommunityTaskBridge bridge,UUID owner,Time time,CommunityTaskClientState.Phase phase) throws Exception {
        for (int i = 0; i < 200; i++) {
            var request = client.nextRequest(); if (request != null) client.accept(bridge.handle(owner,request));
            if (client.phase() == phase) return;
            assertNotEquals(CommunityTaskClientState.Phase.ERROR,client.phase(),client.error());
            time.advance(50); Thread.sleep(5);
        }
        fail("Protocol did not reach "+phase+"; state="+client.phase());
    }
    @Test void endToEndOpenUploadSearchMultiChunkAndDownloadOnlyYieldPrivateReceipt() throws Exception {
        var time = new Time(); var backend = new Backend(time); UUID owner = UUID.randomUUID();
        try (var bridge = new CommunityTaskBridge(tasks(time,backend),time)) {
            var client = new CommunityTaskClientState(time.nanos::get); client.open(); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.IDLE);
            client.search(search()); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
            assertEquals(7,client.result().candidates().size()); assertTrue(client.result().json().toString().length() > 8192);
            client.download("rel_0",false); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
            assertTrue(client.receiptId().startsWith("receipt_")); assertFalse(Files.exists(temp.resolve("world/formacraft/components")));
            client.cancel(); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.CLOSED);
        }
    }
    @Test void fullSizeSearchUsesChunksAndDoesNotStartUntilSubmit() throws Exception {
        var time = new Time(); var backend = new Backend(time); UUID owner = UUID.randomUUID();
        try (var bridge = new CommunityTaskBridge(tasks(time,backend),time)) {
            UUID session = open(bridge,owner); byte[] small = search(), full = new byte[CommunityTaskNetworking.MAX_REQUEST]; Arrays.fill(full,(byte)' '); System.arraycopy(small,0,full,0,small.length);
            UUID task = submit(bridge,owner,session,full); assertNotNull(task);
            Response response = null;
            for (int i = 0; i < 100; i++) { response = bridge.handle(owner,request(Operation.POLL,session,task,0,0,null)); if (response.view() != null && response.view().status().equals("SUCCEEDED")) break; time.advance(50); Thread.sleep(5); }
            assertNotNull(response);
            assertNotNull(response.view());
            assertEquals("SUCCEEDED",response.view().status()); assertEquals(1,backend.calls);
        }
    }
    @Test void foreignSessionAndOldCloseCannotAffectNewEpoch() throws Exception {
        var time = new Time(); var backend = new Backend(time); UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        try (var bridge = new CommunityTaskBridge(tasks(time,backend),time)) {
            UUID old = open(bridge,owner); assertEquals("session_expired",bridge.handle(other,request(Operation.CLOSE,old,null,0,0,null)).code());
            UUID current = open(bridge,owner); assertNotEquals(old,current);
            assertEquals("session_expired",bridge.handle(owner,request(Operation.CLOSE,old,null,0,0,null)).code());
            assertEquals(Reply.ACK,bridge.handle(owner,request(Operation.SEARCH_BEGIN,current,UUID.randomUUID(),0,1,null)).reply());
        }
    }
    @Test void wrongOffsetsIncompleteUploadsAndOldUploadIdsCannotSubmitQueries() throws Exception {
        var time = new Time(); var backend = new Backend(time); UUID owner = UUID.randomUUID();
        try (var bridge = new CommunityTaskBridge(tasks(time,backend),time)) {
            UUID session = open(bridge,owner), a = UUID.randomUUID(), b = UUID.randomUUID();
            bridge.handle(owner,request(Operation.SEARCH_BEGIN,session,a,0,10,null));
            assertEquals("invalid_transfer",bridge.handle(owner,request(Operation.SEARCH_CHUNK,session,a,1,0,new byte[1])).code());
            bridge.handle(owner,request(Operation.SEARCH_BEGIN,session,b,0,10,null));
            assertEquals("stale_upload",bridge.handle(owner,request(Operation.SEARCH_SUBMIT,session,a,0,0,null)).code());
            assertEquals("invalid_transfer",bridge.handle(owner,request(Operation.SEARCH_SUBMIT,session,b,0,0,null)).code()); assertEquals(0,backend.calls);
            bridge.handle(owner,request(Operation.SEARCH_BEGIN,session,a,0,10,null)); time.advance(30_001);
            assertEquals(Reply.ERROR,bridge.handle(owner,request(Operation.SEARCH_SUBMIT,session,a,0,0,null)).reply()); assertEquals(0,backend.calls);
        }
    }
    @Test void lateRepliesAndResultHashMismatchCannotPublishClientCandidates() throws Exception {
        var time = new Time(); var backend = new Backend(time); UUID owner = UUID.randomUUID();
        try (var bridge = new CommunityTaskBridge(tasks(time,backend),time)) {
            var client = new CommunityTaskClientState(time.nanos::get); client.open(); var old = client.nextRequest(); var oldReply = bridge.handle(owner,old);
            client.open(); assertFalse(client.accept(oldReply)); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.IDLE);
            client.search(search());
            for (int i = 0; i < 100; i++) {
                var outbound = client.nextRequest();
                if (outbound != null) {
                    var response = bridge.handle(owner,outbound);
                    if (response.reply() == Reply.CHUNK) {
                        var corrupt = response.data(); corrupt[0] ^= 1;
                        response = new Response(response.token(),response.reply(),response.sessionId(),response.subjectId(),response.view(),response.offset(),response.length(),response.sha256(),corrupt,response.code());
                    }
                    client.accept(response);
                }
                if (client.phase() == CommunityTaskClientState.Phase.ERROR) break; time.advance(50); Thread.sleep(5);
            }
            assertEquals(CommunityTaskClientState.Phase.ERROR,client.phase()); assertEquals("invalid_response",client.error()); assertNull(client.result());
        }
    }
    @Test void cancellingWhileSubmitResponseIsUnknownClosesTheWholeMatchingSession() throws Exception {
        var time = new Time(); var backend = new Backend(time); UUID owner = UUID.randomUUID();
        try (var bridge = new CommunityTaskBridge(tasks(time,backend),time)) {
            var client = new CommunityTaskClientState(time.nanos::get); client.open(); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.IDLE); client.search(search());
            Response late = null;
            for (int i = 0; i < 10; i++) { var outgoing = client.nextRequest(); assertNotNull(outgoing); var response = bridge.handle(owner,outgoing); if (outgoing.operation() == Operation.SEARCH_SUBMIT) { late = response; break; } client.accept(response); }
            assertNotNull(late); client.cancel(); var close = client.nextRequest();
            assertNotNull(close);
            assertEquals(Operation.CLOSE,close.operation());
            assertFalse(client.accept(late)); assertTrue(client.accept(bridge.handle(owner,close))); assertEquals(CommunityTaskClientState.Phase.CLOSED,client.phase());
        }
    }
    @Test void requestTimeoutAndDisconnectNeverResurrectAnOldClientState() throws Exception {
        var time = new Time(); var backend = new Backend(time); UUID owner = UUID.randomUUID();
        try (var bridge = new CommunityTaskBridge(tasks(time,backend),time)) {
            var client = new CommunityTaskClientState(time.nanos::get); client.open(); var outgoing = client.nextRequest(); var response = bridge.handle(owner,outgoing);
            time.advance(10_001); assertNull(client.nextRequest()); assertEquals("request_timeout",client.error()); assertFalse(client.accept(response));
            client.open(); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.IDLE); client.clear(); assertFalse(client.accept(response));
            bridge.disconnect(owner);
        }
    }
    @Test void ingressGateBoundsSchedulingBeforeMainThreadAndReleaseIsIdempotent() {
        var clock = new AtomicLong(); var gate = new CommunityMessageGate(clock::get); UUID owner = UUID.randomUUID();
        var a = gate.acquire(owner); var b = gate.acquire(owner); assertNotNull(a); assertNotNull(b); assertNull(gate.acquire(owner));
        gate.disconnect(owner); assertNull(gate.acquire(owner)); a.close(); a.close(); b.close();
        for (int i = 2; i < CommunityMessageGate.PER_SECOND; i++) { var permit = gate.acquire(owner); assertNotNull(permit); permit.close(); }
        assertNull(gate.acquire(owner)); clock.addAndGet(1_000_000_000L); var fresh = gate.acquire(owner); assertNotNull(fresh); fresh.close();
        gate.close(); assertNull(gate.acquire(UUID.randomUUID()));
    }
    private static CommunityTaskClientState downloaded(CommunityTaskBridge bridge,UUID owner,Time time) throws Exception {
        var client=new CommunityTaskClientState(time.nanos::get); client.open(); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.IDLE);
        client.search(search()); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
        client.download("rel_0",false); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY); return client;
    }
    @Test void explicitPreviewThenConfirmationInstallsPrivatelyAndRetainsSelectedSource() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("library"));
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID();
        try(var coordinator=tasks(time,backend); var bridge=new CommunityTaskBridge(coordinator,time)) {
            var client=downloaded(bridge,owner,time); String receipt=client.receiptId();
            client.previewInstall(); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
            assertNotNull(client.preview()); assertEquals("rel_0",client.preview().releaseId()); assertEquals("DENIED",client.preview().trainingPermission());
            assertFalse(Files.exists(temp.resolve("library")),"Preview must not install");
            UUID approval=client.taskId(); client.confirmInstall(); assertThrows(IOException.class,client::confirmInstall);
            pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
            assertNotNull(client.installed()); assertEquals(receipt,client.installed().receiptId());
            var selection=coordinator.selection(owner,client.sessionId(),client.taskId()); assertEquals(receipt,selection.receipt().receiptId());
            assertEquals("Author",selection.source().manifest().author());
            assertEquals(client.installed().assetId(),selection.assetId());
            assertTrue(Files.isDirectory(ComponentStorage.getPlayerComponentDir(owner).resolve("packages").resolve(selection.assetId())));
            assertFalse(Files.exists(temp.resolve("library/packages"))); assertNull(client.preview());
            assertThrows(IOException.class,()->coordinator.install(owner,client.sessionId(),approval));
            assertThrows(IOException.class,()->coordinator.selection(UUID.randomUUID(),client.sessionId(),client.taskId()));
            Path raw=temp.resolve("world/formacraft/community/players").resolve(owner.toString()).resolve("downloads/objects")
                    .resolve(backend.candidates.get("rel_0").releaseRef().packageSha256()).resolve("original.fccomponent.json");
            Files.writeString(raw,"changed after install");
            assertEquals("cache_corrupt",assertThrows(CommunityPackages.Failure.class,()->coordinator.selection(owner,client.sessionId(),client.taskId())).code());
        } finally { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    }
    @Test void confirmationRequiresCurrentUnexpiredPlayerPreview() throws Exception {
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID();
        try(var coordinator=tasks(time,backend); var bridge=new CommunityTaskBridge(coordinator,time)) {
            var client=downloaded(bridge,owner,time);
            assertThrows(IOException.class,()->coordinator.install(owner,client.sessionId(),client.taskId()));
            client.previewInstall(); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY); UUID approval=client.taskId();
            assertThrows(IOException.class,()->coordinator.install(UUID.randomUUID(),client.sessionId(),approval));
            time.advance(120_001);
            assertEquals("preview_expired",assertThrows(CommunityPackages.Failure.class,()->coordinator.install(owner,client.sessionId(),approval)).code());
            UUID newer=coordinator.connect(owner);
            assertThrows(IOException.class,()->coordinator.install(owner,client.sessionId(),approval));
            assertThrows(IOException.class,()->coordinator.install(owner,newer,approval));
        }
    }
    @Test void installationReusesExistingContentButKeepsTheDownloadedLicenseSelection() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("library"));
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID();
        try(var coordinator=tasks(time,backend); var bridge=new CommunityTaskBridge(coordinator,time)) {
            var source=backend.index.download(backend.candidates.get("rel_0").releaseRef(),ENV).verified();
            var differentRights=new ComponentPackageIO.Manifest("alice","1","Alice","A terms",ComponentPackageIO.TrainingPermission.ALLOWED,"1.21.10",Map.of(),Map.of(),List.of());
            var a=ComponentPackageIO.create(source.component(),differentRights,ENV); Path path=Files.write(temp.resolve("a.json"),a.toBytes());
            var existing=ComponentPackageInstallations.importPackage(path,ENV,new ComponentStorage.SaveContext(temp.resolve("world"),owner,false,false));
            var client=downloaded(bridge,owner,time); client.previewInstall(); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
            client.confirmInstall(); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
            assertEquals(existing.assetId(),client.installed().assetId()); assertEquals("SOURCE_ADDED",client.installed().status());
            var selected=coordinator.selection(owner,client.sessionId(),client.taskId());
            assertEquals(ComponentPackageIO.TrainingPermission.DENIED,selected.source().manifest().trainingPermission());
            assertEquals("Author",selected.source().manifest().author());
            assertEquals("Alice",ComponentPackageInstallations.inspect(ComponentStorage.getPlayerComponentDir(owner),existing.assetId()).manifest().author());
        } finally { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    }
    @Test void cancellingQueuedConfirmationPreventsAnyPrivateLibraryWrite() throws Exception {
        ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("library"));
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID();
        var holding=new java.util.concurrent.atomic.AtomicBoolean(); var started=new java.util.concurrent.CountDownLatch(2); var release=new java.util.concurrent.CountDownLatch(1);
        CommunityTaskCoordinator.Backend blocking=new CommunityTaskCoordinator.Backend() {
            public CommunitySearchResponse.Decoded search(byte[] bytes,CommunitySearchHttpClient.Cancellation cancellation) throws IOException {
                if(holding.get()) { started.countDown(); try { release.await(); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); } }
                return backend.search(bytes,cancellation);
            }
            public CommunitySearchHttpClient.Fetched download(CommunityPackages.Descriptor d,ComponentPackageIO.Environment e,Path p,CommunityTaskCoordinator.Transfer t,CommunitySearchHttpClient.Cancellation c) throws IOException { return backend.download(d,e,p,t,c); }
            public void shutdownNow() { release.countDown(); }
        };
        try(var coordinator=new CommunityTaskCoordinator(Files.createDirectories(temp.resolve("world")),"test",ENV,time,blocking); var bridge=new CommunityTaskBridge(coordinator,time)) {
            var client=downloaded(bridge,owner,time); client.previewInstall(); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
            UUID approval=client.taskId(); holding.set(true);
            for(int i=0;i<2;i++) { UUID other=UUID.randomUUID(); coordinator.search(other,coordinator.connect(other),search()); }
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
            UUID install=coordinator.install(owner,client.sessionId(),approval); assertEquals(CommunityTaskCoordinator.Status.QUEUED,coordinator.snapshot(owner,client.sessionId()).status());
            coordinator.cancel(owner,client.sessionId(),install); release.countDown();
            assertEquals(CommunityTaskCoordinator.Status.CANCELLED,coordinator.snapshot(owner,client.sessionId()).status());
            assertFalse(Files.exists(temp.resolve("library")));
        } finally { release.countDown(); ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    }
    @Test void previewRechecksPrivateRawObjectInsteadOfTrustingDownloadStatus() throws Exception {
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID();
        try(var bridge=new CommunityTaskBridge(tasks(time,backend),time)) {
            var client=downloaded(bridge,owner,time);
            Path raw=temp.resolve("world/formacraft/community/players").resolve(owner.toString()).resolve("downloads/objects")
                    .resolve(backend.candidates.get("rel_0").releaseRef().packageSha256()).resolve("original.fccomponent.json");
            Files.writeString(raw,"corrupt"); client.previewInstall(); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.ERROR);
            assertEquals("cache_corrupt",client.error()); assertNull(client.preview()); assertThrows(IOException.class,client::confirmInstall);
        }
    }
    @Test void installPreviewDocumentsRejectUnknownFieldsAndForgedRights() throws Exception {
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID();
        try(var coordinator=tasks(time,backend); var bridge=new CommunityTaskBridge(coordinator,time)) {
            var client=downloaded(bridge,owner,time); client.previewInstall(); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
            String json=coordinator.snapshot(owner,client.sessionId()).document();
            var unknown=JsonParser.parseString(json).getAsJsonObject(); unknown.addProperty("path","secret");
            assertThrows(IOException.class,()->CommunityInstallDocument.decodePreview(unknown.toString().getBytes(StandardCharsets.UTF_8)));
            var rights=JsonParser.parseString(json).getAsJsonObject(); rights.addProperty("trainingPermission","VERIFIED");
            assertThrows(IOException.class,()->CommunityInstallDocument.decodePreview(rights.toString().getBytes(StandardCharsets.UTF_8)));
            assertThrows(IOException.class,()->CommunityInstallDocument.decodePreview(new byte[CommunityInstallDocument.MAX_BYTES+1]));
            client.cancel(); assertNull(client.preview()); assertThrows(IOException.class,client::confirmInstall);
        }
    }
    private static CommunityTaskClientState installed(CommunityTaskBridge bridge,UUID owner,Time time) throws Exception {
        var client=downloaded(bridge,owner,time); client.previewInstall(); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
        client.confirmInstall(); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY); return client;
    }
    @Test void communityRecipeKeepsChosenRightsAfterLocalReuseAndSurvivesOriginalCacheDeletion() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("library"));
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID();
        try(var coordinator=tasks(time,backend); var bridge=new CommunityTaskBridge(coordinator,time)) {
            var source=backend.index.download(backend.candidates.get("rel_0").releaseRef(),ENV).verified();
            var a=ComponentPackageIO.create(source.component(),new ComponentPackageIO.Manifest("alice","1","Alice","A terms",ComponentPackageIO.TrainingPermission.ALLOWED,"1.21.10",Map.of(),Map.of(),List.of()),ENV);
            var imported=ComponentPackageInstallations.importPackage(Files.write(temp.resolve("a.json"),a.toBytes()),ENV,new ComponentStorage.SaveContext(temp.resolve("world"),owner,false,false));
            var client=installed(bridge,owner,time); assertEquals(imported.assetId(),client.installed().assetId()); String receipt=client.receiptId();
            client.saveRecipe(new ComponentRecipeLibrary.Spec("Chosen B",1,1,1)); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
            assertNotNull(client.recipe()); String recipe=client.recipe().assetId(); assertTrue(ComponentRecipeLibrary.isRecipe(recipe));
            var result=ComponentRecipeLibrary.result(owner,recipe); assertEquals("Author",result.manifest().author()); assertEquals(ComponentPackageIO.TrainingPermission.DENIED,result.manifest().trainingPermission());
            assertEquals(receipt,client.recipe().receiptId()); assertEquals("Author",coordinator.selection(owner,client.sessionId(),client.taskId()).source().manifest().author());
            client.saveRecipe(new ComponentRecipeLibrary.Spec("Different display name",1,1,1)); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
            assertEquals(recipe,client.recipe().assetId()); assertEquals("REUSED",client.recipe().status()); assertEquals("Chosen B",client.recipe().name());
            client.cancel(); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.CLOSED);
            Path downloads=temp.resolve("world/formacraft/community/players").resolve(owner.toString()).resolve("downloads");
            Files.delete(downloads.resolve("objects").resolve(backend.candidates.get("rel_0").releaseRef().packageSha256()).resolve("original.fccomponent.json"));
            Files.delete(downloads.resolve("receipts").resolve(receipt).resolve("receipt.json"));
            ComponentRecipeLibrary.replay(owner,recipe);
            var restored=ComponentRecipeLibrary.restoreSelected(new ComponentStorage.SaveContext(temp.resolve("world"),owner,false,false),recipe);
            assertEquals("Author",restored.source().manifest().author()); assertEquals(receipt,restored.receipt().receiptId());
            assertEquals("Alice",ComponentPackageInstallations.inspect(ComponentStorage.getPlayerComponentDir(owner),imported.assetId()).manifest().author());
        } finally { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    }
    @Test void recipeRequestCannotUseAnotherPlayerOldTaskOrMalformedOptions() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("library"));
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID();
        try(var coordinator=tasks(time,backend); var bridge=new CommunityTaskBridge(coordinator,time)) {
            var client=installed(bridge,owner,time); UUID source=client.taskId(); var spec=new ComponentRecipeLibrary.Spec("Source",1,1,1);
            assertThrows(IOException.class,()->coordinator.recipe(UUID.randomUUID(),client.sessionId(),source,spec));
            assertThrows(IOException.class,()->client.saveRecipe(new ComponentRecipeLibrary.Spec("bad",0,1,1)));
            assertEquals(source,client.taskId()); assertEquals(CommunityTaskClientState.Phase.READY,client.phase());
            var oversized=request(Operation.SAVE_RECIPE,client.sessionId(),source,0,0,new byte[CommunityRecipeOptions.MAX_BYTES+1]);
            assertEquals(Reply.ERROR,bridge.handle(owner,oversized).reply()); assertEquals(source,coordinator.snapshot(owner,client.sessionId()).taskId());
            client.saveRecipe(spec); var request=client.nextRequest(); assertNotNull(request); client.accept(bridge.handle(owner,request));
            assertThrows(IOException.class,()->coordinator.recipe(owner,client.sessionId(),source,spec));
            pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY); UUID favorite=client.taskId();
            client.search(search()); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
            assertNull(client.recipe()); assertNull(client.installed()); assertThrows(IOException.class,()->coordinator.recipe(owner,client.sessionId(),favorite,spec));
        } finally { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    }
    @Test void recipeDoesNotRelaxFixedAuthorDimensions() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("library"));
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID();
        try(var bridge=new CommunityTaskBridge(tasks(time,backend),time)) {
            var client=installed(bridge,owner,time); client.saveRecipe(new ComponentRecipeLibrary.Spec("Invalid stretch",1,2,1));
            pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY); assertEquals("recipe_failed",client.error()); assertNotNull(client.installed());
            assertTrue(ComponentRecipeLibrary.entries(owner).isEmpty());
            client.saveRecipe(new ComponentRecipeLibrary.Spec("Valid original size",1,1,1)); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
            assertNotNull(client.recipe()); assertEquals("",client.error());
        } finally { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    }
    @Test void manualClientBindingNeverFallsBackAfterCommunityTaskChanges() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("library"));
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID();
        try(var coordinator=tasks(time,backend); var bridge=new CommunityTaskBridge(coordinator,time)) {
            var client=installed(bridge,owner,time); String asset=client.installed().assetId(); var binding=new com.formacraft.client.component.CommunityPlacementSelection();
            binding.bind(client); assertEquals(client.sessionId(),binding.reference(client,asset).sessionId()); assertNull(binding.reference(client,"other"));
            time.advance(100_000); bridge.touch(owner,client.sessionId()); time.advance(30_000); bridge.expire(); assertNotNull(coordinator.snapshot(owner,client.sessionId()));
            assertThrows(IOException.class,()->bridge.touch(owner,UUID.randomUUID()));
            client.saveRecipe(new ComponentRecipeLibrary.Spec("Same source",1,1,1)); assertThrows(IllegalStateException.class,()->binding.reference(client,asset));
            pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY); assertThrows(IllegalStateException.class,()->binding.reference(client,asset));
            binding.bind(client); assertTrue(binding.active(client)); client.cancel(); assertThrows(IllegalStateException.class,()->binding.reference(client,asset));
            binding.clear(); assertNull(binding.reference(client,asset));
        } finally { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    }
    private static void await(java.util.concurrent.CountDownLatch latch) throws IOException {
        try { if(!latch.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Test latch timed out"); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
    }
    @Test void selectedPreparationPublishesOnlyTheLatestRequestAndChecksOwner() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("library"));
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID(); var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        try(var coordinator=tasks(time,backend); var bridge=new CommunityTaskBridge(coordinator,time)) {
            var client=installed(bridge,owner,time); UUID session=client.sessionId(), source=client.taskId();
            assertThrows(IOException.class,()->coordinator.useSelection(UUID.randomUUID(),session,source,s -> "bad"));
            var first=coordinator.useSelection(owner,session,source,s -> { entered.countDown(); await(release); return s.source().manifest().author(); }); await(entered);
            var second=coordinator.useSelection(owner,session,source,s -> s.source().manifest().author());
            assertEquals("Author",second.result().get(5,java.util.concurrent.TimeUnit.SECONDS)); assertTrue(coordinator.selectedRequestCurrent(owner,session,source,second.id()));
            assertFalse(coordinator.selectedRequestCurrent(owner,session,source,first.id())); release.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class,()->first.result().get(5,java.util.concurrent.TimeUnit.SECONDS));
            coordinator.invalidateSelectedRequests(owner); assertFalse(coordinator.selectedRequestCurrent(owner,session,source,second.id()));
            var third=coordinator.useSelection(owner,session,source,s -> s.receipt().receiptId()); third.result().get(5,java.util.concurrent.TimeUnit.SECONDS);
            coordinator.search(owner,session,search()); assertFalse(coordinator.selectedRequestCurrent(owner,session,source,third.id()));
            assertThrows(IOException.class,()->coordinator.useSelection(owner,session,source,s -> "stale"));
        } finally { release.countDown(); ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    }
    @Test void selectedPreparationDeadlineAndCloseNeverPermitLatePublication() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("library"));
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID(); var release=new java.util.concurrent.CountDownLatch(1); var entered=new java.util.concurrent.CountDownLatch(1);
        try(var coordinator=tasks(time,backend); var bridge=new CommunityTaskBridge(coordinator,time)) {
            var client=installed(bridge,owner,time); UUID session=client.sessionId(), source=client.taskId();
            var request=coordinator.useSelection(owner,session,source,s -> { entered.countDown(); await(release); return "late"; }); await(entered); time.advance(45_001);
            assertTrue(coordinator.selectedRequestLatest(owner,session,source,request.id())); assertFalse(coordinator.selectedRequestCurrent(owner,session,source,request.id()));
            release.countDown(); assertThrows(java.util.concurrent.ExecutionException.class,()->request.result().get(5,java.util.concurrent.TimeUnit.SECONDS));
            var hold=new java.util.concurrent.CountDownLatch(1); var running=new java.util.concurrent.CountDownLatch(1);
            var pending=coordinator.useSelection(owner,session,source,s -> { running.countDown(); await(hold); return "closed"; }); await(running);
            coordinator.close(); assertThrows(java.util.concurrent.ExecutionException.class,()->pending.result().get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(coordinator.selectedRequestCurrent(owner,session,source,pending.id())); hold.countDown();
        } finally { release.countDown(); ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    }
    @Test void selectedPreparationSharesTheExistingBoundedWorkerQueue() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("library"));
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID(); var release=new java.util.concurrent.CountDownLatch(1);
        try(var coordinator=tasks(time,backend); var bridge=new CommunityTaskBridge(coordinator,time)) {
            var client=installed(bridge,owner,time); UUID session=client.sessionId(), source=client.taskId();
            var requests=new ArrayList<CommunityTaskCoordinator.SelectedRequest<String>>();
            for(int i=0;i<2;i++) { var entered=new java.util.concurrent.CountDownLatch(1); requests.add(coordinator.useSelection(owner,session,source,s -> { entered.countDown(); await(release); return "blocked"; })); await(entered); }
            for(int i=0;i<CommunityTaskCoordinator.QUEUE_SIZE;i++) requests.add(coordinator.useSelection(owner,session,source,s -> "queued"));
            assertEquals("budget_exceeded",assertThrows(CommunityPackages.Failure.class,()->coordinator.useSelection(owner,session,source,s -> "overflow")).code());
            coordinator.close(); for(var request:requests) assertTrue(request.result().isCompletedExceptionally());
        } finally { release.countDown(); ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    }
    private record Favorite(String recipe,String receipt,UUID oldSession,UUID oldTask,String asset) {}
    private Favorite favorite(Time time,Backend backend,UUID owner) throws Exception {
        try(var bridge=new CommunityTaskBridge(tasks(time,backend),time)) {
            var client=installed(bridge,owner,time); client.saveRecipe(new ComponentRecipeLibrary.Spec("Offline source",1,1,1));
            pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
            return new Favorite(client.recipe().assetId(),client.receiptId(),client.sessionId(),client.taskId(),client.installed().assetId());
        }
    }
    private void removeOriginalDownload(Backend backend,UUID owner,Favorite favorite) throws IOException {
        Path root=temp.resolve("world/formacraft/community/players").resolve(owner.toString()).resolve("downloads");
        Files.delete(root.resolve("objects").resolve(backend.candidates.get("rel_0").releaseRef().packageSha256()).resolve("original.fccomponent.json"));
        Files.delete(root.resolve("receipts").resolve(favorite.receipt()).resolve("receipt.json"));
    }
    @Test void newOfflineSessionRestoresPinnedSourceAndPreparesManualPlacementWithoutOriginalDownload() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("library"));
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID();
        try {
            var favorite=favorite(time,backend,owner); removeOriginalDownload(backend,owner,favorite); int networkCalls=backend.calls;
            assertTrue(ComponentRecipeLibrary.entries(owner).stream().filter(e -> e.id.equals(favorite.recipe())).findFirst().orElseThrow().communityRecipe);
            var original=ComponentStorage.loadCatalog(temp.resolve("world"),owner).components.stream().filter(e -> e.id.equals(favorite.asset())).findFirst().orElseThrow();
            ComponentLibraryOperations.delete(new ComponentStorage.SaveContext(temp.resolve("world"),owner,false,false),original.id,original.packageRef);
            assertTrue(ComponentStorage.loadCatalog(temp.resolve("world"),owner).components.stream().noneMatch(e -> e.id.equals(favorite.asset())));
            Path next=Files.createDirectories(temp.resolve("next-world"));
            try(var coordinator=CommunityTaskCoordinator.local(next,ENV,time); var bridge=new CommunityTaskBridge(coordinator,time)) {
                var client=new CommunityTaskClientState(time.nanos::get); client.restoreRecipe(favorite.recipe()); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
                assertEquals("local",client.view().authorityId()); assertEquals("test",client.restoredSource().authority()); assertEquals("rel_0",client.restoredSource().releaseId());
                assertEquals("DENIED",client.restoredSource().trainingPermission()); assertEquals(favorite.receipt(),client.receiptId()); assertTrue(ComponentIds.isSafeId(client.installed().assetId()));
                assertNotEquals(favorite.oldSession(),client.sessionId()); assertThrows(IOException.class,()->coordinator.selection(owner,favorite.oldSession(),favorite.oldTask()));
                var binding=new com.formacraft.client.component.CommunityPlacementSelection(); binding.bind(client); assertTrue(binding.active(client));
                var prepared=coordinator.useSelection(owner,client.sessionId(),client.taskId(),selected -> SelectedComponentPlacement.compile(next,owner,selected,
                        client.installed().assetId(),net.minecraft.util.math.Direction.EAST,com.formacraft.common.component.transform.Mirror.NONE,false,null,7));
                assertEquals(favorite.receipt(),prepared.result().get(5,java.util.concurrent.TimeUnit.SECONDS).receiptId());
                client.saveRecipe(new ComponentRecipeLibrary.Spec("Offline reused",1,1,1)); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
                assertEquals(favorite.recipe(),client.recipe().assetId()); assertEquals("REUSED",client.recipe().status()); assertEquals(networkCalls,backend.calls);
                UUID other=UUID.randomUUID(), session=coordinator.connect(other); coordinator.search(other,session,search());
                for(int i=0;i<200 && coordinator.snapshot(other,session).status()!=CommunityTaskCoordinator.Status.FAILED;i++) Thread.sleep(5);
                assertEquals("community_disabled",coordinator.snapshot(other,session).code());
            }
        } finally { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    }
    @Test void offlineRestoreRejectsOtherOwnersAndPlainRecipesWithoutCreatingTheirCache() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("library"));
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID(), stranger=UUID.randomUUID();
        try {
            var favorite=favorite(time,backend,owner); Path next=Files.createDirectories(temp.resolve("next-world"));
            var entry=ComponentStorage.loadCatalog(temp.resolve("world"),owner).components.stream().filter(e -> e.id.equals(favorite.asset())).findFirst().orElseThrow();
            var plain=ComponentRecipeLibrary.save(temp.resolve("world"),owner,entry.id,entry.packageRef,new ComponentRecipeLibrary.Spec("Plain",1,1,1));
            assertFalse(ComponentRecipeLibrary.entries(owner).stream().filter(e -> e.id.equals(plain.assetId())).findFirst().orElseThrow().communityRecipe);
            try(var bridge=new CommunityTaskBridge(CommunityTaskCoordinator.local(next,ENV,time),time)) {
                var client=new CommunityTaskClientState(time.nanos::get); client.restoreRecipe(favorite.recipe()); pump(client,bridge,stranger,time,CommunityTaskClientState.Phase.ERROR);
                assertEquals("restore_failed",client.error()); assertNull(client.installed()); assertFalse(Files.exists(next.resolve("formacraft/community/players").resolve(stranger.toString())));
                client.restoreRecipe(plain.assetId()); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.ERROR); assertEquals("restore_failed",client.error());
                assertThrows(IOException.class,()->client.restoreRecipe("../someone"));
            }
        } finally { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    }
    @Test void brokenPinnedReceiptCannotRestoreOrCreateANewSelection() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("library"));
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID();
        try {
            var favorite=favorite(time,backend,owner);
            Path raw=ComponentStorage.getPlayerComponentDir(owner).resolve("community_downloads/objects").resolve(backend.candidates.get("rel_0").releaseRef().packageSha256()).resolve("original.fccomponent.json");
            Files.writeString(raw,"broken archive"); Path next=Files.createDirectories(temp.resolve("next-world"));
            try(var bridge=new CommunityTaskBridge(CommunityTaskCoordinator.local(next,ENV,time),time)) {
                var client=new CommunityTaskClientState(time.nanos::get); client.restoreRecipe(favorite.recipe()); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.ERROR);
                assertEquals("cache_corrupt",client.error()); assertNull(client.installed()); assertNull(client.restoredSource());
                assertFalse(Files.exists(next.resolve("formacraft/community/players").resolve(owner.toString())));
            }
        } finally { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    }
    @Test void restoreDocumentsAndLateOpenResponsesCannotSubstituteAnotherReceipt() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("library"));
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID();
        try {
            var favorite=favorite(time,backend,owner); Path next=Files.createDirectories(temp.resolve("next-world"));
            try(var coordinator=CommunityTaskCoordinator.local(next,ENV,time); var bridge=new CommunityTaskBridge(coordinator,time)) {
                var client=new CommunityTaskClientState(time.nanos::get); client.restoreRecipe(favorite.recipe()); var open=client.nextRequest(); var late=bridge.handle(owner,open);
                client.restoreRecipe(favorite.recipe()); assertFalse(client.accept(late)); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
                var json=JsonParser.parseString(coordinator.snapshot(owner,client.sessionId()).document()).getAsJsonObject();
                json.getAsJsonObject("source").addProperty("receiptId","receipt_"+"1".repeat(64));
                assertThrows(IOException.class,()->CommunityInstallDocument.decodeRestored(json.toString().getBytes(StandardCharsets.UTF_8)));
                var binding=new com.formacraft.client.component.CommunityPlacementSelection(); binding.bind(client); client.cancel(); assertThrows(IllegalStateException.class,()->binding.reference(client,favorite.asset()));
            }
        } finally { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    }
    @Test void cancellingQueuedOfflineRestoreCannotPublishAPrivateCache() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("library"));
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID(), other=UUID.randomUUID(); var release=new java.util.concurrent.CountDownLatch(1);
        try {
            var favorite=favorite(time,backend,owner); var otherFavorite=favorite(time,backend,other); Path next=Files.createDirectories(temp.resolve("next-world"));
            try(var coordinator=CommunityTaskCoordinator.local(next,ENV,time); var bridge=new CommunityTaskBridge(coordinator,time)) {
                var client=new CommunityTaskClientState(time.nanos::get); client.restoreRecipe(favorite.recipe()); pump(client,bridge,owner,time,CommunityTaskClientState.Phase.READY);
                for(int i=0;i<2;i++) { var entered=new java.util.concurrent.CountDownLatch(1); coordinator.useSelection(owner,client.sessionId(),client.taskId(),selected -> { entered.countDown(); await(release); return "hold"; }); await(entered); }
                UUID session=coordinator.connect(other), restore=coordinator.restore(other,session,otherFavorite.recipe());
                assertEquals(CommunityTaskCoordinator.Status.QUEUED,coordinator.snapshot(other,session).status()); coordinator.cancel(other,session,restore); release.countDown();
                assertEquals(CommunityTaskCoordinator.Status.CANCELLED,coordinator.snapshot(other,session).status());
                assertFalse(Files.exists(next.resolve("formacraft/community/players").resolve(other.toString())));
            }
        } finally { release.countDown(); ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    }
    @Test void queuedRecipeCancellationDoesNotPublishAFavorite() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("library"));
        var time=new Time(); var backend=new Backend(time); UUID owner=UUID.randomUUID();
        var holding=new java.util.concurrent.atomic.AtomicBoolean(); var started=new java.util.concurrent.CountDownLatch(2); var release=new java.util.concurrent.CountDownLatch(1);
        CommunityTaskCoordinator.Backend blocking=new CommunityTaskCoordinator.Backend() {
            public CommunitySearchResponse.Decoded search(byte[] bytes,CommunitySearchHttpClient.Cancellation cancellation) throws IOException {
                if(holding.get()) { started.countDown(); try { release.await(); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); } }
                return backend.search(bytes,cancellation);
            }
            public CommunitySearchHttpClient.Fetched download(CommunityPackages.Descriptor d,ComponentPackageIO.Environment e,Path p,CommunityTaskCoordinator.Transfer t,CommunitySearchHttpClient.Cancellation c) throws IOException { return backend.download(d,e,p,t,c); }
            public void shutdownNow() { release.countDown(); }
        };
        try(var coordinator=new CommunityTaskCoordinator(Files.createDirectories(temp.resolve("world")),"test",ENV,time,blocking); var bridge=new CommunityTaskBridge(coordinator,time)) {
            var client=installed(bridge,owner,time); holding.set(true);
            for(int i=0;i<2;i++) { UUID other=UUID.randomUUID(); coordinator.search(other,coordinator.connect(other),search()); }
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
            UUID recipe=coordinator.recipe(owner,client.sessionId(),client.taskId(),new ComponentRecipeLibrary.Spec("Cancelled",1,1,1));
            assertEquals(CommunityTaskCoordinator.Status.QUEUED,coordinator.snapshot(owner,client.sessionId()).status());
            coordinator.cancel(owner,client.sessionId(),recipe); release.countDown();
            assertEquals(CommunityTaskCoordinator.Status.CANCELLED,coordinator.snapshot(owner,client.sessionId()).status()); assertTrue(ComponentRecipeLibrary.entries(owner).isEmpty());
        } finally { release.countDown(); ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    }
}
