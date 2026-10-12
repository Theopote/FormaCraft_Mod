package com.formacraft.server.patch;

import com.formacraft.FormacraftMod;
import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.PatchExecutor;
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
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.UUID;

/**
 * 签发 Patch 预览票据并在确认时应用（服务端权威）。
 * <p>
 * 契约：预览时过滤 → Ticket 仅存 accepted → 确认前校验玩家/维度/距离 → 一次性消费 → 写入。
 * 确认路径不再二次过滤，也不接受客户端上传的任意 Patch 列表。
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
        if (!(player.getEntityWorld() instanceof ServerWorld sw)) {
            return null;
        }

        BuildQualityReport quality = PatchQualityChecker.check(sw, origin, rawPatches);
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

        Identifier dimension = sw.getRegistryKey().getValue();
        PreviewTicket ticket = PreviewTicketStorage.create(
                player.getUuid(), dimension, origin, filtered.accepted);
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

        // 门禁先于 consume：失败时保留票据，避免「太远/错维度」导致一次性票据丢失
        PreviewTicket pending = PreviewTicketStorage.get(ticketId);
        if (pending == null || !player.getUuid().equals(pending.playerId())) {
            FormacraftMod.LOGGER.warn("Player {} tried to confirm unknown/expired/foreign patch ticket {}",
                    player.getName().getString(), ticketId);
            return false;
        }

        Identifier currentDim = sw.getRegistryKey().getValue();
        if (pending.dimension() != null && !pending.dimension().equals(currentDim)) {
            FormacraftMod.LOGGER.warn("Player {} patch ticket dimension mismatch: ticket={} current={}",
                    player.getName().getString(), pending.dimension(), currentDim);
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                    "预览维度与当前世界不一致，请回到原维度后确认，或重新预览。"
            ));
            return false;
        }

        if (!isPlayerNear(player, pending.origin())) {
            FormacraftMod.LOGGER.warn("Player {} too far from patch ticket origin {}",
                    player.getName().getString(), pending.origin());
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(
                    "距离预览原点过远，请靠近后再确认（票据仍有效）。"
            ));
            return false;
        }

        BuildQualityReport qr = PreviewStorage.getQualityReport(player);
        if (qr != null && qr.hasError()) {
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                    "【注意】" + qr.summaryZh() + "（仍可应用，请确认后再操作）"
            ));
        }

        PreviewTicket ticket = PreviewTicketStorage.consume(ticketId, player.getUuid());
        if (ticket == null) {
            FormacraftMod.LOGGER.warn("Player {} patch ticket raced/expired at consume {}",
                    player.getName().getString(), ticketId);
            return false;
        }

        List<BlockPatch> patches = ticket.patches();
        if (patches == null || patches.isEmpty()) return false;

        PatchExecutor.ApplyResult applyResult = PatchHistoryManager.applyWithHistory(
                sw, player.getUuid(), ticket.origin(), patches);
        if (applyResult != null) {
            FormaCraftServerNetworking.sendPatchApplyResult(player, applyResult);
        }
        PreviewStorage.clearQualityReport(player);
        return true;
    }

    private static boolean isPlayerNear(ServerPlayerEntity player, BlockPos origin) {
        if (player == null || origin == null) return false;
        double d2 = player.squaredDistanceTo(origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5);
        return d2 <= MAX_APPLY_DISTANCE * MAX_APPLY_DISTANCE;
    }
}
