package com.formacraft.server.preview;

import com.formacraft.common.patch.BlockPatch;
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
        return create(playerId, origin, patches, DEFAULT_TTL_MS);
    }

    public static PreviewTicket create(UUID playerId, BlockPos origin, List<BlockPatch> patches, long ttlMs) {
        if (playerId == null || origin == null || patches == null || patches.isEmpty()) {
            return null;
        }
        purgeExpired();
        long now = System.currentTimeMillis();
        UUID id = UUID.randomUUID();
        PreviewTicket ticket = new PreviewTicket(id, playerId, origin, List.copyOf(patches), now + Math.max(1_000L, ttlMs));
        TICKETS.put(id, ticket);
        return ticket;
    }

    public static PreviewTicket get(UUID ticketId) {
        if (ticketId == null) return null;
        purgeExpired();
        PreviewTicket ticket = TICKETS.get(ticketId);
        if (ticket == null || ticket.isExpired(System.currentTimeMillis())) {
            if (ticket != null) TICKETS.remove(ticketId);
            return null;
        }
        return ticket;
    }

    /**
     * 取出并移除票据（一次性消费，防止重复 apply）。
     */
    public static PreviewTicket consume(UUID ticketId, UUID playerId) {
        PreviewTicket ticket = get(ticketId);
        if (ticket == null) return null;
        if (playerId == null || !playerId.equals(ticket.playerId())) {
            return null;
        }
        TICKETS.remove(ticketId);
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
    }
}
