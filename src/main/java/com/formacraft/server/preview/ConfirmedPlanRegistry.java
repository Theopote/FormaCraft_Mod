package com.formacraft.server.preview;

import com.formacraft.server.generation.typology.builder.PalacePreviewPlan;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Confirmed typology PreviewPlans.
 * <p>
 * Lifecycle keys:
 * <ul>
 *   <li>{@code requestId} — pending bind before a Ticket exists</li>
 *   <li>{@code ticketId} — confirmed instance (unique per preview); NOT planId</li>
 *   <li>{@code planId} — content identity only (may repeat across players/tickets)</li>
 * </ul>
 */
public final class ConfirmedPlanRegistry {
    private ConfirmedPlanRegistry() {}

    private static final long DEFAULT_TTL_MS = 5L * 60L * 1000L;
    /** ticketId → bound plan instance */
    private static final Map<UUID, BoundEntry> BY_TICKET_ID = new ConcurrentHashMap<>();
    /** requestId → pending plan awaiting ticket issue */
    private static final Map<UUID, Pending> BY_REQUEST_ID = new ConcurrentHashMap<>();

    public record BoundEntry(
            UUID ticketId,
            UUID playerId,
            UUID requestId,
            String worldKey,
            BlockPos origin,
            String planId,
            PalacePreviewPlan plan,
            long expiresAtMillis
    ) {
        public boolean isExpired(long now) {
            return now > expiresAtMillis;
        }
    }

    public record Pending(
            UUID requestId,
            UUID playerId,
            String worldKey,
            BlockPos origin,
            PalacePreviewPlan plan,
            long expiresAtMillis
    ) {
        public boolean isExpired(long now) {
            return now > expiresAtMillis;
        }
    }

    public static void putPending(GenerationRequestContext.Scope scope, PalacePreviewPlan plan) {
        if (scope == null || plan == null) return;
        putPending(scope.requestId(), scope.playerId(), scope.worldKey(), scope.origin(), plan);
    }

    public static void putPending(
            UUID requestId,
            UUID playerId,
            String worldKey,
            BlockPos origin,
            PalacePreviewPlan plan
    ) {
        if (requestId == null || plan == null || origin == null) return;
        purgeExpired();
        long exp = System.currentTimeMillis() + DEFAULT_TTL_MS;
        BY_REQUEST_ID.put(requestId, new Pending(
                requestId,
                playerId,
                worldKey == null ? "unknown" : worldKey,
                origin.toImmutable(),
                plan,
                exp));
    }

    /**
     * @deprecated use overload with playerId
     */
    @Deprecated
    public static void putPending(UUID requestId, String worldKey, BlockPos origin, PalacePreviewPlan plan) {
        putPending(requestId, null, worldKey, origin, plan);
    }

    /**
     * Claim pending for this request. Validates player ownership when the pending
     * entry recorded a non-null playerId.
     */
    public static PalacePreviewPlan takePending(
            UUID requestId,
            UUID playerId,
            String worldKey,
            BlockPos origin
    ) {
        if (requestId == null || origin == null) return null;
        purgeExpired();
        Pending p = BY_REQUEST_ID.get(requestId);
        if (p == null || p.isExpired(System.currentTimeMillis())) {
            if (p != null) BY_REQUEST_ID.remove(requestId, p);
            return null;
        }
        String wk = worldKey == null ? "unknown" : worldKey;
        if (!wk.equals(p.worldKey()) || !origin.equals(p.origin())) {
            return null;
        }
        if (p.playerId() != null) {
            if (playerId == null || !p.playerId().equals(playerId)) {
                return null; // ownership mismatch — do not remove
            }
        }
        if (!BY_REQUEST_ID.remove(requestId, p)) {
            return null;
        }
        return p.plan();
    }

    /** Bind a plan instance to a specific preview ticket (unique). */
    public static void putForTicket(
            UUID ticketId,
            UUID playerId,
            UUID requestId,
            String worldKey,
            BlockPos origin,
            PalacePreviewPlan plan,
            long ttlMs
    ) {
        if (ticketId == null || playerId == null || plan == null) return;
        purgeExpired();
        long exp = System.currentTimeMillis() + Math.max(1_000L, ttlMs);
        BY_TICKET_ID.put(ticketId, new BoundEntry(
                ticketId,
                playerId,
                requestId,
                worldKey == null ? "unknown" : worldKey,
                origin == null ? BlockPos.ORIGIN : origin.toImmutable(),
                plan.planId(),
                plan,
                exp));
    }

    public static PalacePreviewPlan getByTicket(UUID ticketId, UUID playerId) {
        BoundEntry e = getEntryByTicket(ticketId, playerId);
        return e == null ? null : e.plan();
    }

    public static BoundEntry getEntryByTicket(UUID ticketId, UUID playerId) {
        if (ticketId == null) return null;
        purgeExpired();
        BoundEntry e = BY_TICKET_ID.get(ticketId);
        if (e == null || e.isExpired(System.currentTimeMillis())) {
            if (e != null) BY_TICKET_ID.remove(ticketId, e);
            return null;
        }
        if (playerId != null && !playerId.equals(e.playerId())) {
            return null;
        }
        return e;
    }

    public static PalacePreviewPlan removeByTicket(UUID ticketId, UUID playerId) {
        if (ticketId == null || playerId == null) return null;
        purgeExpired();
        BoundEntry e = BY_TICKET_ID.get(ticketId);
        if (e == null) return null;
        if (!playerId.equals(e.playerId())) return null;
        if (!BY_TICKET_ID.remove(ticketId, e)) return null;
        return e.plan();
    }

    /** Drop pending for a request (cancel / failed issue). */
    public static void dropPending(UUID requestId) {
        if (requestId == null) return;
        BY_REQUEST_ID.remove(requestId);
    }

    public static void clearPlayer(UUID playerId) {
        if (playerId == null) return;
        BY_TICKET_ID.entrySet().removeIf(e ->
                e.getValue() != null && playerId.equals(e.getValue().playerId()));
        BY_REQUEST_ID.entrySet().removeIf(e ->
                e.getValue() != null
                        && e.getValue().playerId() != null
                        && playerId.equals(e.getValue().playerId()));
    }

    public static void purgeExpired() {
        long now = System.currentTimeMillis();
        BY_TICKET_ID.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isExpired(now));
        BY_REQUEST_ID.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isExpired(now));
    }

    public static void clearAllForTest() {
        BY_TICKET_ID.clear();
        BY_REQUEST_ID.clear();
    }

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

    public static int boundTicketCountForTest() {
        purgeExpired();
        return BY_TICKET_ID.size();
    }

    // --- deprecated planId-keyed API (removed behavior; kept to catch call sites) ---

    /** @deprecated use {@link #putForTicket} */
    @Deprecated
    public static void put(UUID playerId, UUID requestId, String worldKey, BlockPos origin,
                           PalacePreviewPlan plan, long ttlMs) {
        // No-op without ticketId — callers must migrate.
        throw new UnsupportedOperationException("use putForTicket(ticketId, ...)");
    }

    /** @deprecated use {@link #getByTicket} */
    @Deprecated
    public static PalacePreviewPlan get(String planId, UUID playerId) {
        throw new UnsupportedOperationException("use getByTicket(ticketId, playerId)");
    }

    /** @deprecated use {@link #removeByTicket} */
    @Deprecated
    public static PalacePreviewPlan remove(String planId, UUID playerId) {
        throw new UnsupportedOperationException("use removeByTicket(ticketId, playerId)");
    }
}
