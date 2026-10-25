package com.formacraft.server.preview;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.ConfirmedPlanIdentity;
import com.formacraft.server.build.quality.BuildQualityGate;
import com.formacraft.server.generation.typology.builder.PalacePreviewPlan;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存中的 Patch 预览票据存储（按 ticketId 索引）。
 * <p>
 * 生命周期契约：{@link #consumeForApply} 一次消费 Ticket 并分离绑定计划；
 * 取消 / 过期 / 玩家清理同样只影响本 Ticket。
 */
public final class PreviewTicketStorage {
    private PreviewTicketStorage() {}

    private static final long DEFAULT_TTL_MS = 5L * 60L * 1000L;
    /** ~5s at 20 TPS — low-frequency active purge when idle. */
    private static final int PURGE_EVERY_TICKS = 100;
    private static final Map<UUID, PreviewTicket> TICKETS = new ConcurrentHashMap<>();
    private static final AtomicInteger SERVER_TICK = new AtomicInteger();

    /**
     * One-shot consumption: ticket removed from storage and bound plan detached.
     * Callers hold {@link #boundPlan()} for post-consume validation; plan is already
     * gone from {@link ConfirmedPlanRegistry}.
     */
    public record TicketConsumption(PreviewTicket ticket, PalacePreviewPlan boundPlan) {
        public TicketConsumption {
            if (ticket == null) throw new IllegalArgumentException("ticket");
        }
    }

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
            if (ticket != null) {
                releaseTicket(ticket);
            }
            return null;
        }
        return ticket;
    }

    /**
     * Unified apply consumption: removes ticket and detaches its bound plan.
     * Repeat calls return null (no double apply).
     */
    public static TicketConsumption consumeForApply(UUID ticketId, UUID playerId) {
        if (ticketId == null || playerId == null) return null;
        purgeExpired();
        PreviewTicket ticket = TICKETS.get(ticketId);
        if (ticket == null) return null;
        if (ticket.isExpired(System.currentTimeMillis())) {
            releaseTicket(ticket);
            return null;
        }
        if (!playerId.equals(ticket.playerId())) {
            return null;
        }
        if (!TICKETS.remove(ticketId, ticket)) {
            return null;
        }
        PalacePreviewPlan plan = ConfirmedPlanRegistry.removeByTicket(ticket.id(), playerId);
        if (ticket.generationRequestId() != null) {
            ConfirmedPlanRegistry.dropPending(ticket.generationRequestId());
        }
        return new TicketConsumption(ticket, plan);
    }

    /**
     * @deprecated prefer {@link #consumeForApply}; still releases bound plan.
     */
    @Deprecated
    public static PreviewTicket consume(UUID ticketId, UUID playerId) {
        TicketConsumption c = consumeForApply(ticketId, playerId);
        return c == null ? null : c.ticket();
    }

    /**
     * Cancel / discard a ticket without applying; clears bound plan + building-edit Pending.
     * Ownership is verified first via {@link #consumeForApply}; unauthorized callers cannot drop Pending.
     */
    public static boolean cancel(UUID ticketId, UUID playerId) {
        TicketConsumption consumed = consumeForApply(ticketId, playerId);
        if (consumed == null) return false;
        com.formacraft.server.edit.BuildingEditPendingRegistry.dropVerified(ticketId);
        return true;
    }

    public static void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, PreviewTicket>> it = TICKETS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PreviewTicket> e = it.next();
            PreviewTicket t = e.getValue();
            if (t == null || t.isExpired(now)) {
                it.remove();
                if (t != null) {
                    ConfirmedPlanRegistry.removeByTicket(t.id(), t.playerId());
                    if (t.generationRequestId() != null) {
                        ConfirmedPlanRegistry.dropPending(t.generationRequestId());
                    }
                    com.formacraft.server.edit.BuildingEditPendingRegistry.dropVerified(t.id());
                }
            }
        }
        ConfirmedPlanRegistry.purgeExpired();
        com.formacraft.server.edit.BuildingEditPendingRegistry.purgeExpired();
    }

    /** Low-frequency active purge from server tick (idle servers still reclaim). */
    public static void onServerTick() {
        if (SERVER_TICK.incrementAndGet() < PURGE_EVERY_TICKS) return;
        SERVER_TICK.set(0);
        purgeExpired();
    }

    public static void clearPlayer(UUID playerId) {
        if (playerId == null) return;
        Iterator<Map.Entry<UUID, PreviewTicket>> it = TICKETS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PreviewTicket> e = it.next();
            PreviewTicket t = e.getValue();
            if (t != null && playerId.equals(t.playerId())) {
                it.remove();
                ConfirmedPlanRegistry.removeByTicket(t.id(), playerId);
                com.formacraft.server.edit.BuildingEditPendingRegistry.dropVerified(t.id());
            }
        }
        ConfirmedPlanRegistry.clearPlayer(playerId);
        com.formacraft.server.edit.BuildingEditPendingRegistry.clearPlayer(playerId);
    }

    /** Test-only full reset of ticket storage. */
    public static void clearAllForTest() {
        TICKETS.clear();
        ConfirmedPlanRegistry.clearAllForTest();
        com.formacraft.server.edit.BuildingEditPendingRegistry.clearForTest();
        SERVER_TICK.set(0);
    }

    public static int sizeForTest() {
        return TICKETS.size();
    }

    private static void releaseTicket(PreviewTicket ticket) {
        TICKETS.remove(ticket.id(), ticket);
        ConfirmedPlanRegistry.removeByTicket(ticket.id(), ticket.playerId());
        if (ticket.generationRequestId() != null) {
            ConfirmedPlanRegistry.dropPending(ticket.generationRequestId());
        }
        com.formacraft.server.edit.BuildingEditPendingRegistry.dropVerified(ticket.id());
    }
}
