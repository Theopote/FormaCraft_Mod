package com.formacraft.server.edit;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ticket-scoped pending building edits (roof / window).
 * Lifecycle follows PreviewTicket: expire / cancel(owner) / disconnect / consume.
 */
public final class BuildingEditPendingRegistry {
    public record Pending(
            UUID ticketId,
            UUID playerId,
            UUID buildingId,
            Identifier dimension,
            long baseRevision,
            BuildingEditOperation operation,
            Set<BlockPos> allowedCells,
            Map<BlockPos, String> expectedWorldStates,
            Map<BlockPos, String> targetWorldStates,
            long expiresAtMillis
    ) {
        public Pending {
            Objects.requireNonNull(ticketId, "ticketId");
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(buildingId, "buildingId");
            Objects.requireNonNull(dimension, "dimension");
            operation = operation == null ? BuildingEditOperation.REPLACE_ROOF_MATERIAL : operation;
            allowedCells = allowedCells == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(allowedCells));
            expectedWorldStates = expectedWorldStates == null ? Map.of() : Map.copyOf(expectedWorldStates);
            targetWorldStates = targetWorldStates == null ? Map.of() : Map.copyOf(targetWorldStates);
        }

        /** Backward-compatible alias used by Phase-1 roof callers/tests. */
        public Map<BlockPos, String> newRoofStates() {
            return targetWorldStates;
        }

        public boolean isExpired(long nowMillis) {
            return nowMillis > expiresAtMillis;
        }

        public BuildingEditScope toScope() {
            return new BuildingEditScope(
                    buildingId,
                    baseRevision,
                    operation,
                    allowedCells,
                    Set.of(),
                    expectedWorldStates
            );
        }

        /** Phase-1 roof shorthand (keeps older call sites compact). */
        public static Pending roof(
                UUID ticketId,
                UUID playerId,
                UUID buildingId,
                Identifier dimension,
                long baseRevision,
                Set<BlockPos> allowedCells,
                Map<BlockPos, String> expectedWorldStates,
                Map<BlockPos, String> targetWorldStates,
                long expiresAtMillis
        ) {
            return new Pending(
                    ticketId, playerId, buildingId, dimension, baseRevision,
                    BuildingEditOperation.REPLACE_ROOF_MATERIAL,
                    allowedCells, expectedWorldStates, targetWorldStates, expiresAtMillis);
        }
    }

    private static final Map<UUID, Pending> BY_TICKET = new ConcurrentHashMap<>();

    private BuildingEditPendingRegistry() {}

    public static void put(Pending pending) {
        if (pending == null) return;
        BY_TICKET.put(pending.ticketId(), pending);
    }

    /** Peek without removing; expired entries are dropped. */
    public static Pending get(UUID ticketId) {
        if (ticketId == null) return null;
        Pending pending = BY_TICKET.get(ticketId);
        if (pending == null) return null;
        if (pending.isExpired(System.currentTimeMillis())) {
            BY_TICKET.remove(ticketId, pending);
            return null;
        }
        return pending;
    }

    public static Pending take(UUID ticketId) {
        if (ticketId == null) return null;
        Pending pending = BY_TICKET.remove(ticketId);
        if (pending != null && pending.isExpired(System.currentTimeMillis())) {
            return null;
        }
        return pending;
    }

    /** Owner-gated drop: foreign players cannot erase another player's pending. */
    public static boolean dropOwned(UUID ticketId, UUID playerId) {
        if (ticketId == null || playerId == null) return false;
        Pending pending = BY_TICKET.get(ticketId);
        if (pending == null) return false;
        if (!playerId.equals(pending.playerId())) return false;
        return BY_TICKET.remove(ticketId, pending);
    }

    /** Unconditional drop after ownership was already verified by ticket storage. */
    public static void dropVerified(UUID ticketId) {
        if (ticketId != null) BY_TICKET.remove(ticketId);
    }

    public static void clearPlayer(UUID playerId) {
        if (playerId == null) return;
        BY_TICKET.entrySet().removeIf(e -> e.getValue() != null && playerId.equals(e.getValue().playerId()));
    }

    public static void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Pending>> it = BY_TICKET.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pending> e = it.next();
            Pending p = e.getValue();
            if (p == null || p.isExpired(now)) {
                it.remove();
            }
        }
    }

    public static void clearForTest() {
        BY_TICKET.clear();
    }

    public static int sizeForTest() {
        return BY_TICKET.size();
    }

    /** @deprecated Use {@link #dropOwned} or {@link #dropVerified}. */
    @Deprecated
    public static void drop(UUID ticketId) {
        dropVerified(ticketId);
    }
}
