package com.formacraft.server.network;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player sliding-window rate limit for component library write paths
 * (save / import / library mutate). Disconnect must not clear the window.
 */
final class ComponentWriteGate {
    static final int MAX_WRITES_PER_WINDOW = 12;
    static final long WINDOW_MS = 60_000L;

    private static final Map<UUID, Deque<Long>> WINDOW_HITS = new ConcurrentHashMap<>();

    private ComponentWriteGate() {}

    /** @return rejection message, or {@code null} when admitted */
    static Text tryAdmit(ServerPlayerEntity player) {
        if (player == null) {
            return Text.literal("写入失败：玩家无效");
        }
        return tryAdmit(player.getUuid());
    }

    static Text tryAdmit(UUID playerId) {
        if (playerId == null) {
            return Text.literal("写入失败：玩家无效");
        }
        long t = System.currentTimeMillis();
        Deque<Long> hits = WINDOW_HITS.computeIfAbsent(playerId, u -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && t - hits.peekFirst() > WINDOW_MS) {
                hits.removeFirst();
            }
            if (hits.size() >= MAX_WRITES_PER_WINDOW) {
                return Text.literal("写入过于频繁，请稍后再试（" + MAX_WRITES_PER_WINDOW + "/分钟）");
            }
            hits.addLast(t);
            return null;
        }
    }

    /** Diagnostics / tests. */
    static int windowHits(UUID playerId) {
        Deque<Long> hits = WINDOW_HITS.get(playerId);
        if (hits == null) return 0;
        synchronized (hits) {
            return hits.size();
        }
    }

    /** Test / server-stop only — never call on player disconnect. */
    static void clearAllForTest() {
        WINDOW_HITS.clear();
    }

    /** Expire empty windows (optional periodic sweep). */
    static void sweepStale(long nowMs) {
        WINDOW_HITS.entrySet().removeIf(e -> {
            Deque<Long> hits = e.getValue();
            synchronized (hits) {
                while (!hits.isEmpty() && nowMs - hits.peekFirst() > WINDOW_MS) {
                    hits.removeFirst();
                }
                return hits.isEmpty();
            }
        });
    }
}
