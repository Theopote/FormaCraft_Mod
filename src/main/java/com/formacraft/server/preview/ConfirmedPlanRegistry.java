package com.formacraft.server.preview;

import com.formacraft.server.generation.typology.builder.PalacePreviewPlan;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Confirmed typology PreviewPlans indexed by {@code generationRequestId},
 * bound to player / world / origin — not by patchHash alone.
 */
public final class ConfirmedPlanRegistry {
    private ConfirmedPlanRegistry() {}

    private static final long DEFAULT_TTL_MS = 5L * 60L * 1000L;
    private static final Map<String, Entry> BY_PLAN_ID = new ConcurrentHashMap<>();
    private static final Map<UUID, Pending> BY_REQUEST_ID = new ConcurrentHashMap<>();

    public record Entry(
            UUID playerId,
            UUID requestId,
            String worldKey,
            BlockPos origin,
            PalacePreviewPlan plan,
            long expiresAtMillis
    ) {
        public boolean isExpired(long now) {
            return now > expiresAtMillis;
        }
    }

    public record Pending(
            UUID requestId,
            String worldKey,
            BlockPos origin,
            PalacePreviewPlan plan,
            long expiresAtMillis
    ) {
        public boolean isExpired(long now) {
            return now > expiresAtMillis;
        }
    }

    /**
     * Register a plan produced during compile/interpret for a specific generation request.
     */
    public static void putPending(
            UUID requestId,
            String worldKey,
            BlockPos origin,
            PalacePreviewPlan plan
    ) {
        if (requestId == null || plan == null || origin == null) return;
        purgeExpired();
        long exp = System.currentTimeMillis() + DEFAULT_TTL_MS;
        BY_REQUEST_ID.put(requestId, new Pending(
                requestId,
                worldKey == null ? "unknown" : worldKey,
                origin.toImmutable(),
                plan,
                exp));
    }

    /**
     * Take pending plan for this request only if world/origin match.
     * Does not transfer ownership across players via content hash.
     */
    public static PalacePreviewPlan takePending(
            UUID requestId,
            UUID playerId,
            String worldKey,
            BlockPos origin
    ) {
        if (requestId == null || origin == null) return null;
        purgeExpired();
        Pending p = BY_REQUEST_ID.remove(requestId);
        if (p == null || p.isExpired(System.currentTimeMillis())) {
            return null;
        }
        String wk = worldKey == null ? "unknown" : worldKey;
        if (!wk.equals(p.worldKey()) || !origin.equals(p.origin())) {
            // Put back — wrong bind attempt should not steal the pending slot silently.
            BY_REQUEST_ID.putIfAbsent(requestId, p);
            return null;
        }
        return p.plan();
    }

    public static void put(UUID playerId, UUID requestId, String worldKey, BlockPos origin,
                           PalacePreviewPlan plan, long ttlMs) {
        if (playerId == null || plan == null || plan.planId() == null || plan.planId().isBlank()) {
            return;
        }
        purgeExpired();
        long exp = System.currentTimeMillis() + Math.max(1_000L, ttlMs);
        BY_PLAN_ID.put(plan.planId(), new Entry(
                playerId,
                requestId,
                worldKey == null ? "unknown" : worldKey,
                origin == null ? BlockPos.ORIGIN : origin.toImmutable(),
                plan,
                exp));
    }

    /** @deprecated use {@link #put(UUID, UUID, String, BlockPos, PalacePreviewPlan, long)} */
    @Deprecated
    public static void put(UUID playerId, PalacePreviewPlan plan, long ttlMs) {
        put(playerId, null, plan == null ? null : plan.context().worldKey(),
                plan == null ? null : plan.context().origin(), plan, ttlMs);
    }

    public static PalacePreviewPlan get(String planId, UUID playerId) {
        Entry e = getEntry(planId, playerId);
        return e == null ? null : e.plan();
    }

    public static Entry getEntry(String planId, UUID playerId) {
        if (planId == null || planId.isBlank()) return null;
        purgeExpired();
        Entry e = BY_PLAN_ID.get(planId);
        if (e == null || e.isExpired(System.currentTimeMillis())) {
            if (e != null) BY_PLAN_ID.remove(planId, e);
            return null;
        }
        if (playerId != null && !playerId.equals(e.playerId())) {
            return null;
        }
        return e;
    }

    public static PalacePreviewPlan remove(String planId, UUID playerId) {
        if (planId == null || planId.isBlank() || playerId == null) return null;
        purgeExpired();
        Entry e = BY_PLAN_ID.get(planId);
        if (e == null) return null;
        if (!playerId.equals(e.playerId())) return null;
        if (!BY_PLAN_ID.remove(planId, e)) return null;
        return e.plan();
    }

    public static void clearPlayer(UUID playerId) {
        if (playerId == null) return;
        BY_PLAN_ID.entrySet().removeIf(e ->
                e.getValue() != null && playerId.equals(e.getValue().playerId()));
    }

    public static void purgeExpired() {
        long now = System.currentTimeMillis();
        BY_PLAN_ID.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isExpired(now));
        BY_REQUEST_ID.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isExpired(now));
    }

    public static void clearAllForTest() {
        BY_PLAN_ID.clear();
        BY_REQUEST_ID.clear();
    }

    /** Test helper: peek pending without removing. */
    public static Pending peekPendingForTest(UUID requestId) {
        if (requestId == null) return null;
        purgeExpired();
        Pending p = BY_REQUEST_ID.get(requestId);
        if (p == null || p.isExpired(System.currentTimeMillis())) return null;
        return p;
    }

    public static int pendingCountForTest() {
        purgeExpired();
        return BY_REQUEST_ID.size();
    }
}
