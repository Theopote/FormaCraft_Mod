package com.formacraft.server.patch;

import com.formacraft.FormacraftMod;
import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchFilterResult;
import com.formacraft.common.patch.history.PatchHistoryManager;
import com.formacraft.server.build.quality.BuildQualityReport;
import com.formacraft.server.build.quality.BuildQualitySeverity;
import com.formacraft.server.build.quality.PatchQualityChecker;
import com.formacraft.server.network.FormaCraftServerNetworking;
import com.formacraft.server.preview.PreviewStorage;
import com.formacraft.server.preview.PreviewTicket;
import com.formacraft.server.preview.PreviewTicketStorage;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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

        ServerWorld world = player.getEntityWorld() instanceof ServerWorld sw ? sw : null;
        BuildQualityReport quality = PatchQualityChecker.check(world, origin, rawPatches);
        quality.logIssues("patch-preview");

        if (!quality.allowPreview()) {
            if (deliverToClient) {
                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(quality.summaryZh()));
            }
            PreviewStorage.storeQualityReport(player, quality);
            return null;
        }

        PatchFilterResult filtered = ServerPatchFilter.filter(
                origin, rawPatches, protectedZones, outline,
                restrictToSelection, selectionMin, selectionMax);

        if (filtered.rejected != null && !filtered.rejected.isEmpty()) {
            quality.stats().rejectedPatches = filtered.rejected.size();
            quality.add(BuildQualitySeverity.INFO, "PATCH_FILTERED",
                    "过滤 " + filtered.rejected.size() + " 个越界 Patch 操作");
        }

        if (filtered.accepted.isEmpty()) {
            quality.add(BuildQualitySeverity.FATAL, "PATCH_ALL_REJECTED",
                    "所有 Patch 均被约束过滤，无法预览");
            if (deliverToClient) {
                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(quality.summaryZh()));
            }
            PreviewStorage.storeQualityReport(player, quality);
            return null;
        }

        PreviewTicket ticket = PreviewTicketStorage.create(
                player.getUuid(), origin, filtered.accepted);
        if (ticket == null) {
            return null;
        }

        PreviewStorage.storeQualityReport(player, quality);

        if (deliverToClient) {
            FormaCraftServerNetworking.sendPatchPreview(
                    player,
                    ticket.id(),
                    origin,
                    filtered.accepted,
                    filtered.rejected
            );
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(quality.summaryZh()));
        }
        return ticket;
    }

    public static boolean confirm(ServerPlayerEntity player, UUID ticketId) {
        if (player == null || ticketId == null) return false;
        if (!(player.getEntityWorld() instanceof ServerWorld sw)) return false;

        BuildQualityReport qr = PreviewStorage.getQualityReport(player);
        if (qr != null && qr.hasError()) {
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                    "【注意】" + qr.summaryZh() + "（仍可应用，请确认后再操作）"
            ));
        }

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
