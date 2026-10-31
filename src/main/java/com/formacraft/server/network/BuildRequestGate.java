package com.formacraft.server.network;

import com.formacraft.server.config.ServerLlmConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Permission, per-player concurrency, and sliding-window rate limits for chat build requests.
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
                return new Rejection(Text.translatable("formacraft.build.error.rate_limit", maxPerWindow,
                        ServerLlmConfig.buildRateLimitWindowSeconds()));
            }
            hits.addLast(t);
        }
        return null;
    }

    public static void release(ServerPlayerEntity player) {
        if (player == null) return;
        AtomicInteger counter = IN_FLIGHT.get(player.getUuid());
        if (counter != null) {
            counter.updateAndGet(v -> Math.max(0, v - 1));
        }
    }
}
