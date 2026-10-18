package com.formacraft.server.preview;

import com.formacraft.server.generation.typology.builder.PalacePreviewPlan;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side registry of confirmed typology PreviewPlans.
 * <p>
 * Compile path registers by {@code patchHash} (no player yet);
 * {@link com.formacraft.server.patch.PatchPreviewService#issuePreview} binds to player + planId.
 */
public final class ConfirmedPlanRegistry {
    private ConfirmedPlanRegistry() {}

    private static final long DEFAULT_TTL_MS = 5L * 60L * 1000L;
    private static final Map<String, Entry> BY_PLAN_ID = new ConcurrentHashMap<>();
    private static final Map<String, Pending> BY_PATCH_HASH = new ConcurrentHashMap<>();

    public record Entry(UUID playerId, PalacePreviewPlan plan, long expiresAtMillis) {
        public boolean isExpired(long now) {
            return now > expiresAtMillis;
        }
    }

    public record Pending(PalacePreviewPlan plan, long expiresAtMillis) {
        public boolean isExpired(long now) {
            return now > expiresAtMillis;
        }
    }

    /** Called from typology interpret/compile before preview ticket exists. */
    public static void putPending(PalacePreviewPlan plan) {
        if (plan == null || plan.patchHash() == null || plan.patchHash().isBlank()) return;
        purgeExpired();
        long exp = System.currentTimeMillis() + DEFAULT_TTL_MS;
        BY_PATCH_HASH.put(plan.patchHash(), new Pending(plan, exp));
    }

    public static PalacePreviewPlan takePendingByPatchHash(String patchHash) {
        if (patchHash == null || patchHash.isBlank()) return null;
        purgeExpired();
        Pending p = BY_PATCH_HASH.remove(patchHash);
        if (p == null || p.isExpired(System.currentTimeMillis())) {
            return null;
        }
        return p.plan();
    }

    public static void put(UUID playerId, PalacePreviewPlan plan, long ttlMs) {
        if (playerId == null || plan == null || plan.planId() == null || plan.planId().isBlank()) {
            return;
        }
        purgeExpired();
        long exp = System.currentTimeMillis() + Math.max(1_000L, ttlMs);
        BY_PLAN_ID.put(plan.planId(), new Entry(playerId, plan, exp));
    }

    public static PalacePreviewPlan get(String planId, UUID playerId) {
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
        return e.plan();
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
        BY_PATCH_HASH.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isExpired(now));
    }

    public static void clearAllForTest() {
        BY_PLAN_ID.clear();
        BY_PATCH_HASH.clear();
    }
}
