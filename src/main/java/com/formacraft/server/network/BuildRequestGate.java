package com.formacraft.server.network;

import com.formacraft.server.config.ServerLlmConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Permission, per-player concurrency, and sliding-window rate limits for chat build requests.
 * Quota covers orchestrator round-trip <b>and</b> compile/preview until {@link #release(Ticket)}.
 * <p>
 * Each admit returns a one-shot {@link Ticket}; {@link #release(Ticket)} is idempotent.
 * Leases expire after {@link #LEASE_TTL_MS} so a missed finish callback cannot lock a player forever.
 */
public final class BuildRequestGate {
    private BuildRequestGate() {}

    /** Default lease for in-flight build/preview work (covers 605s orchestrator timeout + compile). */
    public static final long LEASE_TTL_MS = 15L * 60L * 1000L;

    private static final Map<UUID, List<Ticket>> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<Long>> WINDOW_HITS = new ConcurrentHashMap<>();
    /** Monotonic per-player preview generation; only the newest ticket may deliver preview. */
    private static final Map<UUID, AtomicLong> PREVIEW_GENERATION = new ConcurrentHashMap<>();

    public record Rejection(Text message) {}

    /** Lifecycle of one admitted build request. */
    public enum RequestState {
        ADMITTED,
        PLANNING,
        COMPILING,
        PREVIEW_READY,
        FINISHED,
        FAILED,
        CANCELLED
    }

    /**
     * One-shot admit token. {@link #release(Ticket)} may be called any number of times;
     * only the first successful release frees the slot.
     */
    public static final class Ticket {
        private final UUID id;
        private final UUID playerId;
        private final long expiresAtMs;
        private final long previewGeneration;
        private final AtomicBoolean released = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final java.util.concurrent.atomic.AtomicReference<RequestState> state =
                new java.util.concurrent.atomic.AtomicReference<>(RequestState.ADMITTED);

        Ticket(UUID playerId, long expiresAtMs, long previewGeneration) {
            this.id = UUID.randomUUID();
            this.playerId = playerId;
            this.expiresAtMs = expiresAtMs;
            this.previewGeneration = previewGeneration;
        }

        public UUID id() {
            return id;
        }

        public UUID playerId() {
            return playerId;
        }

        public long previewGeneration() {
            return previewGeneration;
        }

        public RequestState state() {
            return state.get();
        }

        /** Advance the request state; cancelled/terminal tickets ignore transitions. */
        public void advance(RequestState next) {
            if (next == null) return;
            state.updateAndGet(cur -> {
                if (cur == RequestState.CANCELLED || cur == RequestState.FINISHED
                        || cur == RequestState.FAILED) {
                    return cur;
                }
                return next;
            });
        }

        long expiresAtMs() {
            return expiresAtMs;
        }

        boolean isReleased() {
            return released.get();
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        /** Soft-cancel: keep quota until {@link #release}, but block preview delivery. */
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                state.set(RequestState.CANCELLED);
            }
        }

        boolean tryMarkReleased() {
            return released.compareAndSet(false, true);
        }

        /** True when this ticket may still deliver preview (not stale / cancelled / expired). */
        public boolean mayDeliverPreview() {
            if (released.get() || cancelled.get()) return false;
            if (System.currentTimeMillis() > expiresAtMs) return false;
            AtomicLong gen = PREVIEW_GENERATION.get(playerId);
            return gen != null && gen.get() == previewGeneration;
        }
    }

    /** Admitted ticket, or rejection when {@code ticket == null}. */
    public record AdmitResult(Ticket ticket, Rejection rejection) {
        public static AdmitResult ok(Ticket ticket) {
            return new AdmitResult(ticket, null);
        }

        public static AdmitResult reject(Rejection rejection) {
            return new AdmitResult(null, rejection);
        }

        public boolean rejected() {
            return rejection != null;
        }
    }

    /**
     * Shared OP/permission gate for build, patch preview/confirm, and undo/redo.
     * @return rejection when unauthorized; {@code null} when allowed
     */
    public static Rejection checkPermission(ServerPlayerEntity player, MinecraftServer server) {
        if (player == null || server == null) {
            return new Rejection(Text.translatable("formacraft.build.error.internal"));
        }
        int required = ServerLlmConfig.requireBuildPermissionLevel(server.isDedicated());
        if (required > 0 && !player.hasPermissionLevel(required)) {
            return new Rejection(Text.translatable("formacraft.build.error.permission", required));
        }
        return null;
    }

    public static AdmitResult tryAdmit(ServerPlayerEntity player, MinecraftServer server) {
        Rejection denied = checkPermission(player, server);
        if (denied != null) {
            return AdmitResult.reject(denied);
        }

        UUID id = player.getUuid();
        int maxConcurrent = Math.max(1, ServerLlmConfig.buildMaxConcurrentPerPlayer());
        int maxPerWindow = Math.max(1, ServerLlmConfig.buildRateLimitPerWindow());
        long windowMs = Math.max(1, ServerLlmConfig.buildRateLimitWindowSeconds()) * 1000L;
        long t = System.currentTimeMillis();

        // Atomic map update avoids orphan-list races with release/expire remove(playerId, leases).
        AdmitResult[] box = new AdmitResult[1];
        IN_FLIGHT.compute(id, (uuid, existing) -> {
            List<Ticket> leases = existing != null ? existing : new ArrayList<>(2);
            synchronized (leases) {
                purgeExpiredLocked(leases, t);
                if (leases.size() >= maxConcurrent) {
                    box[0] = AdmitResult.reject(new Rejection(Text.translatable("formacraft.build.error.concurrent")));
                    return leases.isEmpty() ? null : leases;
                }

                Deque<Long> hits = WINDOW_HITS.computeIfAbsent(id, u -> new ArrayDeque<>());
                synchronized (hits) {
                    while (!hits.isEmpty() && t - hits.peekFirst() > windowMs) {
                        hits.removeFirst();
                    }
                    if (hits.size() >= maxPerWindow) {
                        box[0] = AdmitResult.reject(new Rejection(Text.translatable(
                                "formacraft.build.error.rate_limit", maxPerWindow,
                                ServerLlmConfig.buildRateLimitWindowSeconds())));
                        return leases.isEmpty() ? null : leases;
                    }
                    hits.addLast(t);
                }

                long gen = PREVIEW_GENERATION.computeIfAbsent(id, u -> new AtomicLong()).incrementAndGet();
                Ticket ticket = new Ticket(id, t + LEASE_TTL_MS, gen);
                leases.add(ticket);
                box[0] = AdmitResult.ok(ticket);
                return leases;
            }
        });
        return box[0] != null ? box[0]
                : AdmitResult.reject(new Rejection(Text.translatable("formacraft.build.error.internal")));
    }

    /** True if {@code ticket} is still the player's newest non-cancelled lease for preview delivery. */
    public static boolean mayDeliverPreview(Ticket ticket) {
        return ticket != null && ticket.mayDeliverPreview();
    }

    /** Cancel every in-flight request for a player (e.g. /forma_cancel). */
    public static int cancelAllInFlight(ServerPlayerEntity player) {
        if (player == null) return 0;
        return cancelAllInFlight(player.getUuid());
    }

    /** Cancel every in-flight request for a player UUID; returns cancelled count. */
    public static int cancelAllInFlight(UUID playerId) {
        if (playerId == null) return 0;
        List<Ticket> leases = IN_FLIGHT.get(playerId);
        if (leases == null) return 0;
        int cancelled = 0;
        synchronized (leases) {
            for (Ticket t : leases) {
                if (t != null && !t.isReleased() && !t.isCancelled()) {
                    t.cancel();
                    cancelled++;
                }
            }
        }
        return cancelled;
    }

    /** Idempotent release of a single admit ticket. */
    public static void release(Ticket ticket) {
        if (ticket == null || !ticket.tryMarkReleased()) {
            return;
        }
        UUID playerId = ticket.playerId();
        IN_FLIGHT.compute(playerId, (uuid, leases) -> {
            if (leases == null) return null;
            synchronized (leases) {
                leases.removeIf(t -> t == null || t.id().equals(ticket.id()) || t.isReleased());
                return leases.isEmpty() ? null : leases;
            }
        });
        pruneWindow(playerId);
    }

    /**
     * Compatibility: release one active lease for the player (oldest first).
     * Prefer {@link #release(Ticket)} when the admit ticket is available.
     */
    public static void release(ServerPlayerEntity player) {
        if (player == null) return;
        releaseOne(player.getUuid());
    }

    public static void release(UUID playerId) {
        releaseOne(playerId);
    }

    /**
     * Drop every in-flight lease for a player (disconnect).
     * Rate-limit {@link #WINDOW_HITS} are intentionally retained so reconnect cannot
     * reset the sliding window; {@link #sweepStale} expires them by TTL.
     */
    public static void releaseAll(UUID playerId) {
        if (playerId == null) return;
        List<Ticket> leases = IN_FLIGHT.remove(playerId);
        if (leases != null) {
            synchronized (leases) {
                for (Ticket t : leases) {
                    if (t != null) {
                        t.tryMarkReleased();
                    }
                }
                leases.clear();
            }
        }
    }

    public static void releaseAll(ServerPlayerEntity player) {
        if (player != null) {
            releaseAll(player.getUuid());
        }
    }

    private static void releaseOne(UUID playerId) {
        if (playerId == null) return;
        boolean[] released = {false};
        IN_FLIGHT.compute(playerId, (uuid, leases) -> {
            if (leases == null) return null;
            synchronized (leases) {
                purgeExpiredLocked(leases, System.currentTimeMillis());
                Iterator<Ticket> it = leases.iterator();
                while (it.hasNext()) {
                    Ticket t = it.next();
                    if (t != null && t.tryMarkReleased()) {
                        released[0] = true;
                        it.remove();
                        break;
                    }
                    if (t == null || t.isReleased()) {
                        it.remove();
                    }
                }
                return leases.isEmpty() ? null : leases;
            }
        });
        if (released[0]) {
            pruneWindow(playerId);
        }
    }

    /** Test / diagnostics. */
    static int inFlight(UUID playerId) {        List<Ticket> leases = IN_FLIGHT.get(playerId);
        if (leases == null) return 0;
        synchronized (leases) {
            purgeExpiredLocked(leases, System.currentTimeMillis());
            return (int) leases.stream().filter(t -> t != null && !t.isReleased()).count();
        }
    }

    /** Test-only: add a live lease without permission / rate checks. */
    static Ticket forceAdmitForTest(UUID playerId) {
        return forceAdmitForTest(playerId, System.currentTimeMillis() + LEASE_TTL_MS);
    }

    /** Test-only: admit with a custom expiry. */
    static Ticket forceAdmitForTest(UUID playerId, long expiresAtMs) {
        long gen = PREVIEW_GENERATION.computeIfAbsent(playerId, u -> new AtomicLong()).incrementAndGet();
        Ticket ticket = new Ticket(playerId, expiresAtMs, gen);
        IN_FLIGHT.compute(playerId, (uuid, existing) -> {
            List<Ticket> leases = existing != null ? existing : new ArrayList<>(2);
            synchronized (leases) {
                leases.add(ticket);
                return leases;
            }
        });
        return ticket;
    }

    /** Test-only: record a rate-limit hit without admitting a lease. */
    static void recordRateHitForTest(UUID playerId) {
        if (playerId == null) return;
        Deque<Long> hits = WINDOW_HITS.computeIfAbsent(playerId, u -> new ArrayDeque<>());
        synchronized (hits) {
            hits.addLast(System.currentTimeMillis());
        }
    }

    /** Test-only: current sliding-window hit count. */
    static int windowHitsForTest(UUID playerId) {
        Deque<Long> hits = WINDOW_HITS.get(playerId);
        if (hits == null) return 0;
        synchronized (hits) {
            return hits.size();
        }
    }

    private static void purgeExpiredLocked(List<Ticket> leases, long nowMs) {
        Iterator<Ticket> it = leases.iterator();
        while (it.hasNext()) {
            Ticket t = it.next();
            if (t == null || t.isReleased() || t.expiresAtMs() <= nowMs) {
                if (t != null) {
                    t.tryMarkReleased();
                }
                it.remove();
            }
        }
    }

    private static void pruneWindow(UUID id) {
        Deque<Long> hits = WINDOW_HITS.get(id);
        if (hits == null) return;
        long windowMs = Math.max(1, ServerLlmConfig.buildRateLimitWindowSeconds()) * 1000L;
        long t = System.currentTimeMillis();
        synchronized (hits) {
            while (!hits.isEmpty() && t - hits.peekFirst() > windowMs) {
                hits.removeFirst();
            }
            if (hits.isEmpty()) {
                WINDOW_HITS.remove(id, hits);
            }
        }
    }

    /**
     * Expire leases past TTL and prune empty rate-limit windows.
     * Safe to call periodically from the server thread.
     */
    public static void sweepStale(long nowMs) {
        long windowMs = Math.max(1, ServerLlmConfig.buildRateLimitWindowSeconds()) * 1000L;
        Iterator<Map.Entry<UUID, Deque<Long>>> it = WINDOW_HITS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Deque<Long>> e = it.next();
            Deque<Long> hits = e.getValue();
            synchronized (hits) {
                while (!hits.isEmpty() && nowMs - hits.peekFirst() > windowMs) {
                    hits.removeFirst();
                }
                if (hits.isEmpty()) {
                    it.remove();
                }
            }
        }
        for (Map.Entry<UUID, List<Ticket>> e : IN_FLIGHT.entrySet()) {
            List<Ticket> leases = e.getValue();
            synchronized (leases) {
                purgeExpiredLocked(leases, nowMs);
            }
        }
        IN_FLIGHT.entrySet().removeIf(e -> {
            List<Ticket> leases = e.getValue();
            synchronized (leases) {
                return leases.isEmpty();
            }
        });
    }
}
