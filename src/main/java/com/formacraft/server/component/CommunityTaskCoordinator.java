package com.formacraft.server.component;

import com.formacraft.common.component.ComponentPackageIO;
import com.formacraft.common.component.community.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Server-owned read-only jobs. No entities, main-thread callbacks, installation, or world edits. */
public final class CommunityTaskCoordinator implements AutoCloseable {
    public static final int MAX_SESSIONS = 32, WORKERS = 2, QUEUE_SIZE = 8, STARTS_PER_MINUTE = 30;
    public enum Kind { SEARCH, DOWNLOAD }
    public enum Status { IDLE, QUEUED, RUNNING, CACHING, SUCCEEDED, FAILED, CANCELLED }
    public enum Transfer { FULL, RESUMABLE }
    public record Snapshot(UUID sessionId, UUID taskId, Kind kind, Status status, String code, String receiptId,
                           UUID catalogTaskId, String searchJson, Instant deadline, Instant catalogExpiresAt) {}
    public record Metrics(int sessions, int activeWorkers, int queuedTasks) {}
    public interface Backend {
        CommunitySearchResponse.Decoded search(byte[] request, CommunitySearchHttpClient.Cancellation cancellation) throws IOException;
        CommunitySearchHttpClient.Fetched download(CommunityPackages.Descriptor selected, ComponentPackageIO.Environment environment,
                Path staging, Transfer transfer, CommunitySearchHttpClient.Cancellation cancellation) throws IOException;
        /** Must initiate shutdown without waiting for worker/network completion. */
        void shutdownNow();
    }
    @FunctionalInterface public interface Persistence {
        String save(Path root, CommunitySearchHttpClient.Fetched fetched, ComponentPackageIO.Environment environment,
                    Clock clock, CommunityDownloadCache.CommitGate gate) throws IOException;
    }
    private static final class Session {
        final UUID owner, id = UUID.randomUUID();
        Instant activeAt, windowAt, catalogExpires = Instant.MIN;
        int starts;
        Job job;
        UUID catalogTask;
        String searchJson;
        Map<String,CommunityPackages.Descriptor> candidates = Map.of();
        Session(UUID owner, Instant now) { this.owner = owner; activeAt = windowAt = now; }
    }
    private static final class Job {
        final UUID id = UUID.randomUUID(), owner, session;
        final Kind kind;
        final Instant created, deadline;
        final CommunitySearchHttpClient.Cancellation cancellation = new CommunitySearchHttpClient.Cancellation();
        final byte[] request;
        final CommunitySearchContract.Request parsed;
        final CommunityPackages.Descriptor selected;
        final Transfer transfer;
        FutureTask<Void> future;
        Status status = Status.QUEUED;
        String code, receiptId;
        Job(Session session, Instant now, byte[] request, CommunitySearchContract.Request parsed, CommunityPackages.Descriptor selected, Transfer transfer) {
            this.owner = session.owner; this.session = session.id; this.created = now;
            this.kind = request == null ? Kind.DOWNLOAD : Kind.SEARCH; this.request = request; this.parsed = parsed;
            this.selected = selected; this.transfer = transfer; deadline = now.plusSeconds(kind == Kind.SEARCH ? 45 : 150);
        }
    }
    private record PrivatePaths(Path downloads, Path staging) {}
    private final Object lock = new Object();
    private final Map<UUID,Session> sessions = new HashMap<>();
    private final Path world;
    private final String authority;
    private final ComponentPackageIO.Environment environment;
    private final Clock clock;
    private final Backend backend;
    private final Persistence persistence;
    private final ThreadPoolExecutor workers;
    private boolean closed;

    public CommunityTaskCoordinator(Path world, String authority, URI base, ComponentPackageIO.Environment environment, Clock clock) {
        this(world,authority,environment,clock,http(authority,base));
    }
    public CommunityTaskCoordinator(Path world, String authority, ComponentPackageIO.Environment environment, Clock clock, Backend backend) {
        this(world,authority,environment,clock,backend,CommunityDownloadCache::save);
    }
    public CommunityTaskCoordinator(Path world, String authority, ComponentPackageIO.Environment environment, Clock clock, Backend backend, Persistence persistence) {
        this.world = Objects.requireNonNull(world).toAbsolutePath().normalize();
        if (authority == null || !authority.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}")) throw new IllegalArgumentException("Invalid authority");
        this.authority = authority; Objects.requireNonNull(environment);
        this.environment = new ComponentPackageIO.Environment(environment.minecraftVersion(),new HashMap<>(environment.installedMods()));
        this.clock = Objects.requireNonNull(clock); this.backend = Objects.requireNonNull(backend);
        this.persistence = Objects.requireNonNull(persistence);
        var count = new AtomicInteger();
        workers = new ThreadPoolExecutor(WORKERS,WORKERS,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(QUEUE_SIZE), runnable -> {
            var thread = new Thread(runnable,"formacraft-community-"+count.incrementAndGet()); thread.setDaemon(true); return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    private static Backend http(String authority, URI base) {
        var client = new CommunitySearchHttpClient(authority,base);
        return new Backend() {
            public CommunitySearchResponse.Decoded search(byte[] request, CommunitySearchHttpClient.Cancellation cancellation) throws IOException { return client.search(request,cancellation); }
            public CommunitySearchHttpClient.Fetched download(CommunityPackages.Descriptor selected, ComponentPackageIO.Environment environment,
                    Path staging, Transfer transfer, CommunitySearchHttpClient.Cancellation cancellation) throws IOException {
                return transfer == Transfer.FULL ? client.download(selected,environment,cancellation) : client.downloadResumable(selected,environment,staging,cancellation);
            }
            public void shutdownNow() { client.shutdownNow(); }
        };
    }
    public UUID connect(UUID owner) throws IOException {
        Objects.requireNonNull(owner);
        synchronized (lock) {
            requireOpen(); expireLocked();
            var existing = sessions.remove(owner); if (existing != null) cancel(existing.job,Status.CANCELLED,"cancelled");
            if (sessions.size() >= MAX_SESSIONS) throw new CommunityPackages.Failure("budget_exceeded");
            var session = new Session(owner,clock.instant()); sessions.put(owner,session); return session.id;
        }
    }
    public UUID search(UUID owner, UUID sessionId, byte[] request) throws IOException {
        synchronized (lock) { session(owner,sessionId); }
        if (request == null || request.length > CommunitySearchContract.MAX_REQUEST_BYTES) throw new CommunityPackages.Failure("invalid_request");
        byte[] owned = request.clone(); var parsed = CommunitySearchContract.decode(owned);
        synchronized (lock) {
            var session = session(owner,sessionId); admit(session);
            session.catalogTask = null; session.searchJson = null; session.candidates = Map.of(); session.catalogExpires = Instant.MIN;
            return start(session,new Job(session,clock.instant(),owned,parsed,null,null));
        }
    }
    public UUID download(UUID owner, UUID sessionId, UUID searchTaskId, String releaseId, Transfer transfer) throws IOException {
        Objects.requireNonNull(transfer);
        synchronized (lock) {
            var session = session(owner,sessionId);
            if (!Objects.equals(searchTaskId,session.catalogTask) || session.catalogTask == null) throw new CommunityPackages.Failure("stale_selection");
            if (!clock.instant().isBefore(session.catalogExpires)) throw new CommunityPackages.Failure("selection_expired");
            var selected = session.candidates.get(releaseId); if (selected == null) throw new CommunityPackages.Failure("invalid_selection");
            admit(session); return start(session,new Job(session,clock.instant(),null,null,selected,transfer));
        }
    }
    public Snapshot snapshot(UUID owner, UUID sessionId) throws IOException {
        synchronized (lock) {
            var session = session(owner,sessionId); expireJob(session.job); var job = session.job;
            return new Snapshot(session.id,job == null ? null : job.id,job == null ? null : job.kind,job == null ? Status.IDLE : job.status,
                    job == null ? null : job.code,job == null ? null : job.receiptId,session.catalogTask,session.searchJson,job == null ? null : job.deadline,session.catalogExpires);
        }
    }
    public Metrics metrics() { synchronized (lock) { return new Metrics(sessions.size(),workers.getActiveCount(),workers.getQueue().size()); } }
    public String authorityId() { return authority; }
    public void cancel(UUID owner, UUID sessionId, UUID taskId) throws IOException {
        synchronized (lock) {
            var session = session(owner,sessionId);
            if (session.job == null || !session.job.id.equals(taskId)) throw new CommunityPackages.Failure("stale_task");
            cancel(session.job,Status.CANCELLED,"cancelled");
        }
    }
    public void disconnect(UUID owner) {
        synchronized (lock) { var session = sessions.remove(owner); if (session != null) cancel(session.job,Status.CANCELLED,"cancelled"); }
    }
    public void expire() { synchronized (lock) { if (!closed) expireLocked(); } }
    private UUID start(Session session, Job job) {
        cancel(session.job,Status.CANCELLED,"cancelled"); session.job = job;
        job.future = new FutureTask<>(() -> { run(job); return null; }) {
            @Override protected void done() { workers.remove(this); }
        };
        try { workers.execute(job.future); }
        catch (RejectedExecutionException full) { job.status = Status.FAILED; job.code = "budget_exceeded"; job.future.cancel(false); }
        return job.id;
    }
    private void run(Job job) {
        try {
            synchronized (lock) { current(job); job.status = Status.RUNNING; }
            if (job.kind == Kind.SEARCH) {
                var result = backend.search(job.request,job.cancellation);
                // Keep the injected backend and the HTTP implementation under the same response contract.
                byte[] json = result.json().toString().getBytes(StandardCharsets.UTF_8);
                var checked = CommunitySearchResponse.decode(json,job.parsed,authority); var candidates = new LinkedHashMap<String,CommunityPackages.Descriptor>();
                for (var candidate : checked.candidates()) candidates.put(candidate.releaseRef().releaseId(),candidate);
                int ttl = checked.json().getAsJsonObject("indexSnapshot").get("expiresInSeconds").getAsInt();
                synchronized (lock) {
                    var session = current(job); session.catalogTask = job.id; session.searchJson = new String(json,StandardCharsets.UTF_8);
                    session.candidates = Map.copyOf(candidates); session.catalogExpires = job.created.plusSeconds(ttl); job.status = Status.SUCCEEDED;
                }
            } else {
                var paths = paths(job.owner); synchronized (lock) { current(job); }
                var fetched = backend.download(job.selected,environment,paths.staging,job.transfer,job.cancellation);
                if (!fetched.download().descriptor().equals(job.selected)) throw new CommunityPackages.Failure("object_changed");
                synchronized (lock) { current(job); job.status = Status.CACHING; }
                String receipt = persistence.save(paths.downloads,fetched,environment,clock,commit -> {
                    synchronized (lock) { current(job); }
                    // Permission precedes this one publication; cancellation never waits for filesystem I/O.
                    commit.run();
                });
                if (!CommunityDownloadCache.receiptId(job.selected).equals(receipt)) throw new CommunityPackages.Failure("cache_corrupt");
                synchronized (lock) { current(job); job.receiptId = receipt; job.status = Status.SUCCEEDED; }
            }
        } catch (Exception failed) {
            synchronized (lock) {
                if (!isCurrent(job)) return;
                job.status = Status.FAILED;
                job.code = failed instanceof CommunityPackages.Failure failure && failure.code() != null && failure.code().matches("[a-z][a-z0-9_]{0,63}")
                        ? failure.code() : failed instanceof IOException ? "storage_failure" : "internal_error";
            }
            if (!(failed instanceof CommunityPackages.Failure))
                com.formacraft.FormacraftMod.LOGGER.warn("Community task failed owner={} task={} kind={}",job.owner,job.id,job.kind,failed);
        }
    }
    private Session current(Job job) throws IOException {
        if (!isCurrent(job)) throw new CommunityPackages.Failure("cancelled");
        if (!clock.instant().isBefore(job.deadline)) throw new CommunityPackages.Failure("request_timeout");
        return sessions.get(job.owner);
    }
    private boolean isCurrent(Job job) {
        var session = sessions.get(job.owner);
        return !closed && session != null && session.id.equals(job.session) && session.job == job && !terminal(job.status);
    }
    private static boolean terminal(Status status) { return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.CANCELLED; }
    private Session session(UUID owner, UUID id) throws IOException {
        requireOpen(); var session = sessions.get(owner);
        if (session == null || !session.id.equals(id)) throw new CommunityPackages.Failure("session_expired");
        if (!clock.instant().isBefore(session.activeAt.plusSeconds(600))) {
            sessions.remove(owner); cancel(session.job,Status.CANCELLED,"cancelled"); throw new CommunityPackages.Failure("session_expired");
        }
        return session;
    }
    private void admit(Session session) throws IOException {
        Instant now = clock.instant();
        if (!now.isBefore(session.windowAt.plusSeconds(60))) { session.starts = 0; session.windowAt = now; }
        if (session.starts >= STARTS_PER_MINUTE) throw new CommunityPackages.Failure("rate_limited");
        session.starts++; session.activeAt = now;
    }
    private void requireOpen() throws IOException { if (closed) throw new CommunityPackages.Failure("community_closed"); }
    private void expireJob(Job job) {
        if (job != null && !terminal(job.status) && !clock.instant().isBefore(job.deadline)) cancel(job,Status.FAILED,"request_timeout");
    }
    private void expireLocked() {
        var iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            var session = iterator.next(); expireJob(session.job);
            if (!clock.instant().isBefore(session.activeAt.plusSeconds(600))) { cancel(session.job,Status.CANCELLED,"cancelled"); iterator.remove(); }
        }
    }
    private static void cancel(Job job, Status status, String code) {
        if (job == null || terminal(job.status)) return;
        job.status = status; job.code = code; job.receiptId = null; job.cancellation.cancel();
        if (job.future != null) job.future.cancel(true);
    }
    private PrivatePaths paths(UUID owner) throws IOException {
        Path parent = world;
        if (!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS) || !parent.toRealPath().equals(parent)) throw new IOException("World directory unavailable");
        for (String name : List.of("formacraft","community","players",owner.toString())) parent = child(parent,name);
        return new PrivatePaths(child(parent,"downloads"),child(parent,"staging"));
    }
    private static Path child(Path parent, String name) throws IOException {
        Path child = parent.resolve(name);
        try { Files.createDirectory(child); } catch (FileAlreadyExistsException exists) { /* Concurrent owners may create shared parents. */ }
        if (!Files.isDirectory(child,LinkOption.NOFOLLOW_LINKS) || !child.toRealPath().equals(parent.toRealPath().resolve(name))) throw new IOException("Community directory redirects");
        return child;
    }
    @Override public void close() {
        synchronized (lock) {
            if (closed) return; closed = true;
            for (var session : sessions.values()) cancel(session.job,Status.CANCELLED,"cancelled"); sessions.clear();
        }
        workers.shutdownNow(); backend.shutdownNow();
    }
}
