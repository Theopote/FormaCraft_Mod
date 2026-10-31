package com.formacraft.server.network;

import com.formacraft.server.config.ServerLlmConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Permission, per-player concurrency, and sliding-window rate limits for chat build requests.
 * Quota covers orchestrator round-trip <b>and</b> compile/preview until {@link #release}.
 */
public final class BuildRequestGate {
    private BuildRequestGate() {}

    private static final Map<UUID, AtomicInteger> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<Long>> WINDOW_HITS = new ConcurrentHashMap<>();

    public record Rejection(Text message) {}

    public static Rejection tryAdmit(ServerPlayerEntity player, MinecraftServer server) {
        if (player == null || server == null) {
            return new Rejection(Text.translatable("formacraft.build.error.internal"));
        }

        int required = ServerLlmConfig.requireBuildPermissionLevel(server.isDedicated());
        if (required > 0 && !player.hasPermissionLevel(required)) {
            return new Rejection(Text.translatable("formacraft.build.error.permission", required));
        }

        UUID id = player.getUuid();
        int maxConcurrent = Math.max(1, ServerLlmConfig.buildMaxConcurrentPerPlayer());
        AtomicInteger counter = IN_FLIGHT.computeIfAbsent(id, u -> new AtomicInteger(0));
        int now = counter.incrementAndGet();
        if (now > maxConcurrent) {
            counter.decrementAndGet();
            pruneIdle(id, counter);
            return new Rejection(Text.translatable("formacraft.build.error.concurrent"));
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
                counter.decrementAndGet();
                pruneIdle(id, counter);
                return new Rejection(Text.translatable("formacraft.build.error.rate_limit", maxPerWindow,
                        ServerLlmConfig.buildRateLimitWindowSeconds()));
            }
            hits.addLast(t);
        }
        return null;
    }

    public static void release(ServerPlayerEntity player) {
        if (player == null) return;
        release(player.getUuid());
    }

    public static void release(UUID playerId) {
        if (playerId == null) return;
        AtomicInteger counter = IN_FLIGHT.get(playerId);
        if (counter != null) {
            counter.updateAndGet(v -> Math.max(0, v - 1));
            pruneIdle(playerId, counter);
        }
        pruneWindow(playerId);
    }

    /** Test / diagnostics. */
    static int inFlight(UUID playerId) {
        AtomicInteger c = IN_FLIGHT.get(playerId);
        return c == null ? 0 : c.get();
    }

    /** Test-only: increment in-flight without permission / rate checks. */
    static void forceAdmitForTest(UUID playerId) {
        IN_FLIGHT.computeIfAbsent(playerId, u -> new AtomicInteger(0)).incrementAndGet();
    }

    private static void pruneIdle(UUID id, AtomicInteger counter) {
        if (counter.get() == 0) {
            IN_FLIGHT.remove(id, counter);
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

    /** Occasional sweep so disconnected players do not retain empty rate-limit deques forever. */
    static void sweepStale(long nowMs) {
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
        IN_FLIGHT.entrySet().removeIf(e -> e.getValue().get() == 0);
    }
}
