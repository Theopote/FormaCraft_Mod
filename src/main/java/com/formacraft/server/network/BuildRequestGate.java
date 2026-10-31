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
            cancelled.set(true);
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

    public static AdmitResult tryAdmit(ServerPlayerEntity player, MinecraftServer server) {
        if (player == null || server == null) {
            return AdmitResult.reject(new Rejection(Text.translatable("formacraft.build.error.internal")));
        }

        int required = ServerLlmConfig.requireBuildPermissionLevel(server.isDedicated());
        if (required > 0 && !player.hasPermissionLevel(required)) {
            return AdmitResult.reject(new Rejection(Text.translatable("formacraft.build.error.permission", required)));
        }

        UUID id = player.getUuid();
        expireLeases(id, System.currentTimeMillis());

        int maxConcurrent = Math.max(1, ServerLlmConfig.buildMaxConcurrentPerPlayer());
        List<Ticket> leases = IN_FLIGHT.computeIfAbsent(id, u -> new ArrayList<>(2));
        synchronized (leases) {
            purgeExpiredLocked(leases, System.currentTimeMillis());
            if (leases.size() >= maxConcurrent) {
                return AdmitResult.reject(new Rejection(Text.translatable("formacraft.build.error.concurrent")));
            }

            int maxPerWindow = Math.max(1, ServerLlmConfig.buildRateLimitPerWindow());
            long windowMs = Math.max(1, ServerLlmConfig.buildRateLimitWindowSeconds()) * 1000L;
            long t = System.currentTimeMillis();
            Deque<Long> hits = WINDOW_HITS.computeIfAbsent(id, u -> new ArrayDeque<>());
            synchronized (hits) {
                while (!hits.isEmpty() && t - hits.peekFirst() > windowMs) {
                    hits.removeFirst();
                }
                if (hits.size() >= maxPerWindow) {
                    return AdmitResult.reject(new Rejection(Text.translatable(
                            "formacraft.build.error.rate_limit", maxPerWindow,
                            ServerLlmConfig.buildRateLimitWindowSeconds())));
                }
                hits.addLast(t);
            }

            long gen = PREVIEW_GENERATION.computeIfAbsent(id, u -> new AtomicLong()).incrementAndGet();
            Ticket ticket = new Ticket(id, t + LEASE_TTL_MS, gen);
            leases.add(ticket);
            return AdmitResult.ok(ticket);
        }
    }

    /** True if {@code ticket} is still the player's newest non-cancelled lease for preview delivery. */
    public static boolean mayDeliverPreview(Ticket ticket) {
        return ticket != null && ticket.mayDeliverPreview();
    }

    /** Idempotent release of a single admit ticket. */
    public static void release(Ticket ticket) {
        if (ticket == null || !ticket.tryMarkReleased()) {
            return;
        }
        UUID playerId = ticket.playerId();
        List<Ticket> leases = IN_FLIGHT.get(playerId);
        if (leases != null) {
            synchronized (leases) {
                leases.removeIf(t -> t == null || t.id().equals(ticket.id()) || t.isReleased());
                if (leases.isEmpty()) {
                    IN_FLIGHT.remove(playerId, leases);
                }
            }
        }
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

    /** Drop every in-flight lease for a player (disconnect). */
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
        WINDOW_HITS.remove(playerId);
    }

    public static void releaseAll(ServerPlayerEntity player) {
        if (player != null) {
            releaseAll(player.getUuid());
        }
    }

    private static void releaseOne(UUID playerId) {
        if (playerId == null) return;
        List<Ticket> leases = IN_FLIGHT.get(playerId);
        if (leases == null) {
            pruneWindow(playerId);
            return;
        }
        Ticket victim = null;
        synchronized (leases) {
            purgeExpiredLocked(leases, System.currentTimeMillis());
            Iterator<Ticket> it = leases.iterator();
            while (it.hasNext()) {
                Ticket t = it.next();
                if (t != null && t.tryMarkReleased()) {
                    victim = t;
                    it.remove();
                    break;
                }
                if (t == null || t.isReleased()) {
                    it.remove();
                }
            }
            if (leases.isEmpty()) {
                IN_FLIGHT.remove(playerId, leases);
            }
        }
        if (victim != null) {
            pruneWindow(playerId);
        }
    }

    /** Test / diagnostics. */
    static int inFlight(UUID playerId) {
        List<Ticket> leases = IN_FLIGHT.get(playerId);
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
        List<Ticket> leases = IN_FLIGHT.computeIfAbsent(playerId, u -> new ArrayList<>(2));
        synchronized (leases) {
            leases.add(ticket);
        }
        return ticket;
    }

    private static void expireLeases(UUID playerId, long nowMs) {
        List<Ticket> leases = IN_FLIGHT.get(playerId);
        if (leases == null) return;
        synchronized (leases) {
            purgeExpiredLocked(leases, nowMs);
            if (leases.isEmpty()) {
                IN_FLIGHT.remove(playerId, leases);
            }
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
