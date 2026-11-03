package com.formacraft.server.component;

import com.formacraft.common.component.*;
import com.formacraft.common.component.community.*;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class CommunityTaskCoordinatorTest {
    @TempDir Path temp;
    private static final ComponentPackageIO.Environment ENV = new ComponentPackageIO.Environment("1.21.10",Map.of());
    private static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-03T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
    }
    private static final class Backend implements CommunityTaskCoordinator.Backend {
        final OfflineCommunityIndex index;
        final CommunityPackages.Descriptor descriptor;
        final Clock clock;
        final AtomicInteger searches = new AtomicInteger(), downloads = new AtomicInteger(), shutdowns = new AtomicInteger();
        final CountDownLatch started, release = new CountDownLatch(1);
        volatile boolean blockDownloads, failSearch;
        volatile Path staging;
        volatile String workerThread;
        Backend(Clock clock,int blockers) throws Exception {
            this.clock = clock; started = new CountDownLatch(blockers);
            var def = new ComponentDefinition(); def.id = "column"; def.name = "Column"; def.category = ComponentCategory.COLUMN; def.sourceOrigin = "CAPTURED";
            def.size = new ComponentDefinition.Size(); def.size.w = def.size.d = def.size.h = 1;
            def.anchor = new ComponentDefinition.Anchor(); def.anchor.facing = "SOUTH";
            var block = new ComponentDefinition.BlockEntry(); block.block = "minecraft:stone"; def.blocks = List.of(block);
            var manifest = new ComponentPackageIO.Manifest("author","1","Author","LicenseRef-Test",ComponentPackageIO.TrainingPermission.DENIED,"1.21.10",Map.of(),Map.of(),List.of());
            var pack = ComponentPackageIO.create(def,manifest,ENV); byte[] bytes = pack.toBytes();
            var ref = new CommunityPackages.ReleaseRef("test","resource","release",CommunityPackages.sha256(bytes),pack.sha256(),pack.manifestSha256());
            descriptor = new CommunityPackages.Descriptor(ref,bytes.length,ComponentPackageIO.SCHEMA,ComponentContentIdentity.fingerprints(def));
            index = new OfflineCommunityIndex("test",clock); index.add(descriptor,bytes);
        }
        private void block() {
            started.countDown();
            // Deliberately ignore cancellation to prove that late work cannot publish.
            while (true) { try { release.await(); return; } catch (InterruptedException ignored) { /* Simulated uncooperative backend. */ } }
        }
        public CommunitySearchResponse.Decoded search(byte[] bytes,CommunitySearchHttpClient.Cancellation cancellation) throws IOException {
            searches.incrementAndGet(); workerThread = Thread.currentThread().getName();
            var request = CommunitySearchContract.decode(bytes);
            if (request.requestId().startsWith("block")) block();
            if (failSearch) throw new IOException("private/path credentials must not reach snapshots");
            return CommunitySearchResponse.decode(CommunitySearchResponse.search(index,request,clock),request,"test");
        }
        public CommunitySearchHttpClient.Fetched download(CommunityPackages.Descriptor selected,ComponentPackageIO.Environment environment,
                Path staging,CommunityTaskCoordinator.Transfer transfer,CommunitySearchHttpClient.Cancellation cancellation) throws IOException {
            downloads.incrementAndGet(); this.staging = staging; workerThread = Thread.currentThread().getName();
            if (blockDownloads) block();
            return fetched();
        }
        CommunitySearchHttpClient.Fetched fetched() throws IOException {
            return new CommunitySearchHttpClient.Fetched(CommunityReleaseDetails.decode(index.releaseDetails(descriptor.releaseRef()),descriptor),index.download(descriptor.releaseRef(),ENV));
        }
        public void shutdownNow() { shutdowns.incrementAndGet(); }
    }
    private Path world() throws IOException { return Files.createDirectories(temp.resolve("world")); }
    private static byte[] request(String id) {
        var root = new JsonObject(); root.addProperty("contract",CommunitySearchContract.CONTRACT); root.addProperty("requestId",id);
        root.addProperty("needId","column"); root.add("query",new JsonObject()); return root.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void failure(String code,org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code,assertThrows(CommunityPackages.Failure.class,operation).code());
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= end) fail("Timed out waiting for bounded task transition");
            Thread.sleep(5);
        }
    }
    private static CommunityTaskCoordinator.Snapshot finished(CommunityTaskCoordinator tasks,UUID owner,UUID session) throws Exception {
        await(() -> { try { var status = tasks.snapshot(owner,session).status(); return status == CommunityTaskCoordinator.Status.SUCCEEDED
                || status == CommunityTaskCoordinator.Status.FAILED || status == CommunityTaskCoordinator.Status.CANCELLED; } catch (IOException failed) { throw new RuntimeException(failed); } });
        return tasks.snapshot(owner,session);
    }
    private static UUID catalog(CommunityTaskCoordinator tasks,UUID owner,UUID session) throws Exception {
        UUID id = tasks.search(owner,session,request("search")); assertEquals(CommunityTaskCoordinator.Status.SUCCEEDED,finished(tasks,owner,session).status()); return id;
    }
    private Path cache(UUID owner) { return temp.resolve("world/formacraft/community/players").resolve(owner.toString()).resolve("downloads"); }

    @Test void searchAndDownloadRunOffCallerThreadAndOnlyCreatePrivateReceipt() throws Exception {
        var clock = new MutableClock(); var backend = new Backend(clock,1); UUID owner = UUID.randomUUID();
        try (var tasks = new CommunityTaskCoordinator(world(),"test",ENV,clock,backend)) {
            UUID session = tasks.connect(owner), search = catalog(tasks,owner,session);
            assertTrue(backend.workerThread.startsWith("formacraft-community-"));
            UUID download = tasks.download(owner,session,search,"release",CommunityTaskCoordinator.Transfer.FULL);
            var result = finished(tasks,owner,session); assertEquals(download,result.taskId()); assertEquals(CommunityTaskCoordinator.Status.SUCCEEDED,result.status());
            assertEquals(CommunityDownloadCache.receiptId(backend.descriptor),result.receiptId());
            assertEquals("Author",CommunityDownloadCache.read(cache(owner),result.receiptId(),ENV).download().verified().manifest().author());
            assertEquals(cache(owner).getParent().resolve("staging"),backend.staging);
            assertFalse(Files.exists(temp.resolve("world/formacraft/components"))); assertFalse(result.toString().contains(temp.toString()));
        }
        assertEquals(1,backend.shutdowns.get());
    }

    @Test void lateSearchCannotReplaceNewerPageEvenWhenItIgnoresCancellation() throws Exception {
        var clock = new MutableClock(); var backend = new Backend(clock,1); UUID owner = UUID.randomUUID();
        try (var tasks = new CommunityTaskCoordinator(world(),"test",ENV,clock,backend)) {
            UUID session = tasks.connect(owner); tasks.search(owner,session,request("block_old")); assertTrue(backend.started.await(5,TimeUnit.SECONDS));
            UUID latest = tasks.search(owner,session,request("latest")); assertEquals(CommunityTaskCoordinator.Status.SUCCEEDED,finished(tasks,owner,session).status());
            backend.release.countDown(); await(() -> tasks.metrics().activeWorkers() == 0);
            var snapshot = tasks.snapshot(owner,session); assertEquals(latest,snapshot.taskId()); assertEquals(latest,snapshot.catalogTaskId());
            assertTrue(snapshot.searchJson().contains("latest")); assertFalse(snapshot.searchJson().contains("block_old"));
        } finally { backend.release.countDown(); }
    }

    @Test void disconnectedLateDownloadCannotSaveReceiptOrAffectReconnectedSession() throws Exception {
        var clock = new MutableClock(); var backend = new Backend(clock,1); UUID owner = UUID.randomUUID();
        try (var tasks = new CommunityTaskCoordinator(world(),"test",ENV,clock,backend)) {
            UUID oldSession = tasks.connect(owner), search = catalog(tasks,owner,oldSession); backend.blockDownloads = true;
            tasks.download(owner,oldSession,search,"release",CommunityTaskCoordinator.Transfer.RESUMABLE); assertTrue(backend.started.await(5,TimeUnit.SECONDS));
            tasks.disconnect(owner); UUID current = tasks.connect(owner); backend.release.countDown(); await(() -> tasks.metrics().activeWorkers() == 0);
            assertEquals(CommunityTaskCoordinator.Status.IDLE,tasks.snapshot(owner,current).status()); assertNull(tasks.snapshot(owner,current).receiptId());
            failure("session_expired",() -> tasks.snapshot(owner,oldSession)); assertFalse(Files.exists(cache(owner).resolve("receipts")));
        } finally { backend.release.countDown(); }
    }

    @Test void onlyCurrentPageCanSelectDownloadAndOldCancelCannotCancelCurrentJob() throws Exception {
        var clock = new MutableClock(); var backend = new Backend(clock,1); UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        try (var tasks = new CommunityTaskCoordinator(world(),"test",ENV,clock,backend)) {
            UUID session = tasks.connect(owner), search = catalog(tasks,owner,session);
            failure("session_expired",() -> tasks.snapshot(other,session));
            failure("invalid_selection",() -> tasks.download(owner,session,search,"foreign",CommunityTaskCoordinator.Transfer.FULL));
            UUID next = tasks.search(owner,session,request("new_page"));
            failure("stale_selection",() -> tasks.download(owner,session,search,"release",CommunityTaskCoordinator.Transfer.FULL));
            failure("stale_task",() -> tasks.cancel(owner,session,search));
            assertEquals(next,finished(tasks,owner,session).taskId()); assertEquals(0,backend.downloads.get());
        }
    }

    @Test void queueBackpressureAndQueuedCancellationNeverUseCallerRuns() throws Exception {
        var clock = new MutableClock(); var backend = new Backend(clock,2); var owners = new ArrayList<UUID>(); var sessions = new ArrayList<UUID>(); var jobs = new ArrayList<UUID>();
        try (var tasks = new CommunityTaskCoordinator(world(),"test",ENV,clock,backend)) {
            for (int i = 0; i < 10; i++) { UUID owner = UUID.randomUUID(); owners.add(owner); UUID session = tasks.connect(owner); sessions.add(session); jobs.add(tasks.search(owner,session,request("block_"+i))); }
            assertTrue(backend.started.await(5,TimeUnit.SECONDS)); assertEquals(2,backend.searches.get()); assertEquals(8,tasks.metrics().queuedTasks());
            UUID rejectedOwner = UUID.randomUUID(), rejectedSession = tasks.connect(rejectedOwner); tasks.search(rejectedOwner,rejectedSession,request("overflow"));
            assertEquals("budget_exceeded",tasks.snapshot(rejectedOwner,rejectedSession).code()); assertEquals(2,backend.searches.get());
            tasks.cancel(owners.get(2),sessions.get(2),jobs.get(2)); assertEquals(7,tasks.metrics().queuedTasks());
            UUID accepted = UUID.randomUUID(), acceptedSession = tasks.connect(accepted); tasks.search(accepted,acceptedSession,request("accepted"));
            assertEquals(8,tasks.metrics().queuedTasks()); assertEquals(CommunityTaskCoordinator.Status.QUEUED,tasks.snapshot(accepted,acceptedSession).status());
        } finally { backend.release.countDown(); }
    }

    @Test void deadlinesCancelQueuedWorkAndLateWorkersCannotReviveFailedTasks() throws Exception {
        var clock = new MutableClock(); var backend = new Backend(clock,2);
        try (var tasks = new CommunityTaskCoordinator(world(),"test",ENV,clock,backend)) {
            UUID a = UUID.randomUUID(), b = UUID.randomUUID(), queued = UUID.randomUUID();
            UUID sa = tasks.connect(a), sb = tasks.connect(b), sq = tasks.connect(queued);
            tasks.search(a,sa,request("block_a")); tasks.search(b,sb,request("block_b")); assertTrue(backend.started.await(5,TimeUnit.SECONDS)); tasks.search(queued,sq,request("queued"));
            clock.advance(46); tasks.expire(); assertEquals("request_timeout",tasks.snapshot(queued,sq).code()); assertEquals(0,tasks.metrics().queuedTasks());
            backend.release.countDown(); await(() -> tasks.metrics().activeWorkers() == 0);
            assertEquals(CommunityTaskCoordinator.Status.FAILED,tasks.snapshot(a,sa).status()); assertNull(tasks.snapshot(a,sa).catalogTaskId());
        } finally { backend.release.countDown(); }
    }

    @Test void candidateExpiryAndSessionExpiryAreDifferentAndSessionSlotsAreReleased() throws Exception {
        var clock = new MutableClock(); var backend = new Backend(clock,1); UUID owner = UUID.randomUUID();
        try (var tasks = new CommunityTaskCoordinator(world(),"test",ENV,clock,backend)) {
            UUID session = tasks.connect(owner), search = catalog(tasks,owner,session);
            clock.advance(300); failure("selection_expired",() -> tasks.download(owner,session,search,"release",CommunityTaskCoordinator.Transfer.FULL));
            clock.advance(301); tasks.expire(); failure("session_expired",() -> tasks.snapshot(owner,session)); assertEquals(0,tasks.metrics().sessions());
            for (int i = 0; i < CommunityTaskCoordinator.MAX_SESSIONS; i++) tasks.connect(UUID.randomUUID());
            failure("budget_exceeded",() -> tasks.connect(UUID.randomUUID())); clock.advance(601); tasks.expire(); assertEquals(0,tasks.metrics().sessions());
        }
    }

    @Test void submissionBudgetDoesNotCancelTheLastAcceptedTask() throws Exception {
        var clock = new MutableClock(); var backend = new Backend(clock,1); UUID owner = UUID.randomUUID();
        try (var tasks = new CommunityTaskCoordinator(world(),"test",ENV,clock,backend)) {
            UUID session = tasks.connect(owner), last = null;
            for (int i = 0; i < CommunityTaskCoordinator.STARTS_PER_MINUTE; i++) last = tasks.search(owner,session,request("fast_"+i));
            failure("rate_limited",() -> tasks.search(owner,session,request("limited"))); assertEquals(last,tasks.snapshot(owner,session).taskId());
            clock.advance(60); tasks.search(owner,session,request("new_window")); assertEquals(CommunityTaskCoordinator.Status.SUCCEEDED,finished(tasks,owner,session).status());
        }
    }

    @Test void concurrentOwnersUseSeparatePrivateDirectoriesAndNoInstallationOccurs() throws Exception {
        var clock = new MutableClock(); var backend = new Backend(clock,1); UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        try (var tasks = new CommunityTaskCoordinator(world(),"test",ENV,clock,backend)) {
            UUID sa = tasks.connect(a), sb = tasks.connect(b), ca = catalog(tasks,a,sa), cb = catalog(tasks,b,sb);
            tasks.download(a,sa,ca,"release",CommunityTaskCoordinator.Transfer.FULL); tasks.download(b,sb,cb,"release",CommunityTaskCoordinator.Transfer.FULL);
            var ra = finished(tasks,a,sa); var rb = finished(tasks,b,sb); assertEquals(CommunityTaskCoordinator.Status.SUCCEEDED,ra.status()); assertEquals(CommunityTaskCoordinator.Status.SUCCEEDED,rb.status());
            assertEquals(ra.receiptId(),rb.receiptId()); assertNotEquals(cache(a),cache(b));
            assertNotNull(CommunityDownloadCache.read(cache(a),ra.receiptId(),ENV)); assertNotNull(CommunityDownloadCache.read(cache(b),rb.receiptId(),ENV));
            assertFalse(Files.exists(temp.resolve("world/formacraft/components")));
        }
    }

    @Test void cancelledReceiptCommitLeavesOnlyAnUnreferencedValidatedObject() throws Exception {
        var clock = new MutableClock(); var backend = new Backend(clock,1); Path cache = temp.resolve("cache"); var commits = new AtomicInteger();
        failure("cancelled",() -> CommunityDownloadCache.save(cache,backend.fetched(),ENV,clock,commit -> {
            if (commits.incrementAndGet() == 2) throw new CommunityPackages.Failure("cancelled"); commit.run();
        }));
        assertEquals(2,commits.get()); try (var files = Files.list(cache.resolve("receipts"))) { assertEquals(0,files.count()); }
        try (var files = Files.list(cache.resolve("objects"))) { assertEquals(1,files.count()); }
        assertThrows(IOException.class,() -> CommunityDownloadCache.read(cache,CommunityDownloadCache.receiptId(backend.descriptor),ENV));
    }

    @Test void persistenceCannotPublishAReceiptIdentityForAnotherSelection() throws Exception {
        var clock = new MutableClock(); var backend = new Backend(clock,1); UUID owner = UUID.randomUUID();
        try (var tasks = new CommunityTaskCoordinator(world(),"test",ENV,clock,backend,(root,fetched,environment,time,gate) -> "receipt_"+"0".repeat(64))) {
            UUID session = tasks.connect(owner), search = catalog(tasks,owner,session);
            tasks.download(owner,session,search,"release",CommunityTaskCoordinator.Transfer.FULL);
            var result = finished(tasks,owner,session); assertEquals("cache_corrupt",result.code()); assertNull(result.receiptId());
        }
    }

    @Test void cancellationDoesNotWaitForAnAlreadyPermittedDiskCommitAndCannotPublishReady() throws Exception {
        var clock = new MutableClock(); var backend = new Backend(clock,1); UUID owner = UUID.randomUUID();
        var permitted = new CountDownLatch(1); var release = new CountDownLatch(1); var commits = new AtomicInteger();
        CommunityTaskCoordinator.Persistence persistence = (root,fetched,environment,time,gate) -> CommunityDownloadCache.save(root,fetched,environment,time,commit -> gate.publish(() -> {
            if (commits.incrementAndGet() == 2) {
                permitted.countDown();
                while (true) { try { release.await(); break; } catch (InterruptedException ignored) { /* Simulated slow filesystem call. */ } }
            }
            commit.run();
        }));
        try (var tasks = new CommunityTaskCoordinator(world(),"test",ENV,clock,backend,persistence)) {
            UUID session = tasks.connect(owner), search = catalog(tasks,owner,session);
            UUID download = tasks.download(owner,session,search,"release",CommunityTaskCoordinator.Transfer.FULL);
            assertTrue(permitted.await(5,TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1),() -> tasks.cancel(owner,session,download));
            assertEquals(CommunityTaskCoordinator.Status.CANCELLED,tasks.snapshot(owner,session).status());
            release.countDown(); await(() -> tasks.metrics().activeWorkers() == 0);
            assertNull(tasks.snapshot(owner,session).receiptId());
            assertNotNull(CommunityDownloadCache.read(cache(owner),CommunityDownloadCache.receiptId(backend.descriptor),ENV));
        } finally { release.countDown(); }
    }

    @Test void failuresAreSanitizedAndShutdownDoesNotWaitForAnUncooperativeBackend() throws Exception {
        var clock = new MutableClock(); var backend = new Backend(clock,1); UUID owner = UUID.randomUUID();
        try (var tasks = new CommunityTaskCoordinator(world(), "test", ENV, clock, backend)) {
            UUID session = tasks.connect(owner);
            backend.failSearch = true;
            tasks.search(owner, session, request("error"));
            var error = finished(tasks, owner, session);
            assertEquals("storage_failure", error.code());
            assertFalse(error.toString().contains("private/path"));
            backend.failSearch = false;
            tasks.search(owner, session, request("block_shutdown"));
            assertTrue(backend.started.await(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), tasks::close);
            assertEquals(1, backend.shutdowns.get());
            failure("community_closed", () -> tasks.snapshot(owner, session));
        } finally {
            backend.release.countDown();
        }
    }
}
