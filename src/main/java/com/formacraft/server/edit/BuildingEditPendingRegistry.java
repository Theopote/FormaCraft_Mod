package com.formacraft.server.edit;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Ticket-scoped pending Phase-1 building edits; committed only after Apply SUCCESS. */
public final class BuildingEditPendingRegistry {
    public record Pending(
            UUID buildingId,
            Identifier dimension,
            long baseRevision,
            Map<BlockPos, String> newRoofStates
    ) {
        public Pending {
            newRoofStates = newRoofStates == null ? Map.of() : Map.copyOf(newRoofStates);
        }
    }

    private static final Map<UUID, Pending> BY_TICKET = new ConcurrentHashMap<>();

    private BuildingEditPendingRegistry() {}

    public static void put(UUID ticketId, Pending pending) {
        if (ticketId == null || pending == null) return;
        BY_TICKET.put(ticketId, pending);
    }

    public static Pending take(UUID ticketId) {
        if (ticketId == null) return null;
        return BY_TICKET.remove(ticketId);
    }

    public static void drop(UUID ticketId) {
        if (ticketId != null) BY_TICKET.remove(ticketId);
    }

    public static void clearForTest() {
        BY_TICKET.clear();
    }
}
