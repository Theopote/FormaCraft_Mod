package com.formacraft.server.patch;

import com.formacraft.FormacraftMod;
import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.server.network.FormaCraftServerNetworking;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchFilterResult;
import com.formacraft.common.patch.history.PatchHistoryManager;
import com.formacraft.server.preview.PreviewTicket;
import com.formacraft.server.preview.PreviewTicketStorage;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.UUID;

/**
 * 签发 Patch 预览票据并在确认时应用（服务端权威）。
 */
public final class PatchPreviewService {
    private PatchPreviewService() {}

    private static final double MAX_APPLY_DISTANCE = 96.0;

    public static PreviewTicket issuePreview(
            ServerPlayerEntity player,
            BlockPos origin,
            List<BlockPatch> rawPatches,
            List<ProtectedZone> protectedZones,
            OutlineShape outline,
            boolean restrictToSelection,
            BlockPos selectionMin,
            BlockPos selectionMax
    ) {
        return issuePreview(player, origin, rawPatches, protectedZones, outline,
                restrictToSelection, selectionMin, selectionMax, true);
    }

    public static PreviewTicket issuePreview(
            ServerPlayerEntity player,
            BlockPos origin,
            List<BlockPatch> rawPatches,
            List<ProtectedZone> protectedZones,
            OutlineShape outline,
            boolean restrictToSelection,
            BlockPos selectionMin,
            BlockPos selectionMax,
            boolean deliverToClient
    ) {
        if (player == null || origin == null || rawPatches == null || rawPatches.isEmpty()) {
            return null;
        }
        if (!isPlayerNear(player, origin)) {
            return null;
        }

        PatchFilterResult filtered = ServerPatchFilter.filter(
                origin, rawPatches, protectedZones, outline,
                restrictToSelection, selectionMin, selectionMax);
        if (filtered.accepted.isEmpty()) {
            return null;
        }

        PreviewTicket ticket = PreviewTicketStorage.create(
                player.getUuid(), origin, filtered.accepted);
        if (ticket == null) {
            return null;
        }

        if (deliverToClient) {
            FormaCraftServerNetworking.sendPatchPreview(
                    player,
                    ticket.id(),
                    origin,
                    filtered.accepted,
                    filtered.rejected
            );
        }
        return ticket;
    }

    public static boolean confirm(ServerPlayerEntity player, UUID ticketId) {
        if (player == null || ticketId == null) return false;
        if (!(player.getEntityWorld() instanceof ServerWorld sw)) return false;

        PreviewTicket ticket = PreviewTicketStorage.consume(ticketId, player.getUuid());
        if (ticket == null) {
            FormacraftMod.LOGGER.warn("Player {} tried to confirm unknown/expired patch ticket {}", 
                    player.getName().getString(), ticketId);
            return false;
        }
        if (!isPlayerNear(player, ticket.origin())) {
            FormacraftMod.LOGGER.warn("Player {} too far from patch ticket origin {}", 
                    player.getName().getString(), ticket.origin());
            return false;
        }

        List<BlockPatch> patches = ticket.patches();
        if (patches == null || patches.isEmpty()) return false;

        PatchHistoryManager.applyWithHistory(sw, player.getUuid(), ticket.origin(), patches);
        return true;
    }

    private static boolean isPlayerNear(ServerPlayerEntity player, BlockPos origin) {
        if (player == null || origin == null) return false;
        double d2 = player.squaredDistanceTo(origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5);
        return d2 <= MAX_APPLY_DISTANCE * MAX_APPLY_DISTANCE;
    }
}
