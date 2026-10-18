package com.formacraft.server.preview;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.ConfirmedPlanIdentity;
import com.formacraft.server.build.quality.BuildQualityGate;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存中的 Patch 预览票据存储（按 ticketId 索引）。
 */
public final class PreviewTicketStorage {
    private PreviewTicketStorage() {}

    private static final long DEFAULT_TTL_MS = 5L * 60L * 1000L;
    private static final Map<UUID, PreviewTicket> TICKETS = new ConcurrentHashMap<>();

    public static PreviewTicket create(UUID playerId, BlockPos origin, List<BlockPatch> patches) {
        return create(playerId, null, origin, patches, null, DEFAULT_TTL_MS,
                null, "", Map.of(), false, null);
    }

    public static PreviewTicket create(UUID playerId, Identifier dimension, BlockPos origin, List<BlockPatch> patches) {
        return create(playerId, dimension, origin, patches, null, DEFAULT_TTL_MS,
                null, "", Map.of(), false, null);
    }

    public static PreviewTicket create(
            UUID playerId,
            Identifier dimension,
            BlockPos origin,
            List<BlockPatch> patches,
            BuildQualityGate.QualitySnapshot quality
    ) {
        return create(playerId, dimension, origin, patches, quality, DEFAULT_TTL_MS,
                null, "", Map.of(), false, null);
    }

    public static PreviewTicket create(UUID playerId, BlockPos origin, List<BlockPatch> patches, long ttlMs) {
        return create(playerId, null, origin, patches, null, ttlMs,
                null, "", Map.of(), false, null);
    }

    public static PreviewTicket create(
            UUID playerId,
            Identifier dimension,
            BlockPos origin,
            List<BlockPatch> patches,
            BuildQualityGate.QualitySnapshot quality,
            long ttlMs
    ) {
        return create(playerId, dimension, origin, patches, quality, ttlMs,
                null, "", Map.of(), false, null);
    }

    /** Compatibility overload used by Round 49 callers. */
    public static PreviewTicket create(
            UUID playerId,
            Identifier dimension,
            BlockPos origin,
            List<BlockPatch> patches,
            BuildQualityGate.QualitySnapshot quality,
            long ttlMs,
            String explicitPlanId,
            String contextHash,
            Map<BlockPos, String> expectedStates
    ) {
        boolean requires = explicitPlanId != null && !explicitPlanId.isBlank()
                && contextHash != null && !contextHash.isBlank();
        return create(playerId, dimension, origin, patches, quality, ttlMs,
                explicitPlanId, contextHash, expectedStates, requires, null);
    }

    public static PreviewTicket create(
            UUID playerId,
            Identifier dimension,
            BlockPos origin,
            List<BlockPatch> patches,
            BuildQualityGate.QualitySnapshot quality,
            long ttlMs,
            String explicitPlanId,
            String contextHash,
            Map<BlockPos, String> expectedStates,
            boolean requiresConfirmedPlan,
            UUID generationRequestId
    ) {
        if (playerId == null || origin == null || patches == null || patches.isEmpty()) {
            return null;
        }
        purgeExpired();
        long now = System.currentTimeMillis();
        UUID id = UUID.randomUUID();
        List<BlockPatch> finalPatches = ConfirmedPlanIdentity.toFinalPatches(patches);
        String patchHash = ConfirmedPlanIdentity.patchHash(finalPatches);
        String ctx = contextHash == null ? "" : contextHash;
        String planId = (explicitPlanId != null && !explicitPlanId.isBlank())
                ? explicitPlanId
                : ConfirmedPlanIdentity.planId(ctx, finalPatches);
        PreviewTicket ticket = new PreviewTicket(
                id, playerId, dimension, origin, finalPatches,
                now + Math.max(1_000L, ttlMs), quality,
                planId, patchHash, ctx,
                expectedStates == null ? Map.of() : expectedStates,
                requiresConfirmedPlan,
                generationRequestId);
        TICKETS.put(id, ticket);
        return ticket;
    }

    public static PreviewTicket get(UUID ticketId) {
        if (ticketId == null) return null;
        purgeExpired();
        PreviewTicket ticket = TICKETS.get(ticketId);
        if (ticket == null || ticket.isExpired(System.currentTimeMillis())) {
            if (ticket != null) TICKETS.remove(ticketId, ticket);
            return null;
        }
        return ticket;
    }

    public static PreviewTicket consume(UUID ticketId, UUID playerId) {
        if (ticketId == null || playerId == null) return null;
        purgeExpired();
        PreviewTicket ticket = TICKETS.get(ticketId);
        if (ticket == null) return null;
        if (ticket.isExpired(System.currentTimeMillis())) {
            TICKETS.remove(ticketId, ticket);
            return null;
        }
        if (!playerId.equals(ticket.playerId())) {
            return null;
        }
        if (!TICKETS.remove(ticketId, ticket)) {
            return null;
        }
        return ticket;
    }

    public static void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, PreviewTicket>> it = TICKETS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PreviewTicket> e = it.next();
            PreviewTicket t = e.getValue();
            if (t == null || t.isExpired(now)) {
                it.remove();
            }
        }
    }

    public static void clearPlayer(UUID playerId) {
        if (playerId == null) return;
        TICKETS.entrySet().removeIf(e -> e.getValue() != null && playerId.equals(e.getValue().playerId()));
        ConfirmedPlanRegistry.clearPlayer(playerId);
    }
}
