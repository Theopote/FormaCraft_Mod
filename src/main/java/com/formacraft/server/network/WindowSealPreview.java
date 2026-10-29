package com.formacraft.server.network;

import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.server.build.BuildExecutionService;
import com.formacraft.server.edit.*;
import com.formacraft.server.patch.PatchPreviewService;
import com.formacraft.server.preview.PreviewEditKind;
import com.formacraft.server.state.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

/** Handles explicit window sealing before a model can return a replacement house. */
final class WindowSealPreview {
    private WindowSealPreview() {}

    static boolean tryHandle(ServerPlayerEntity player, FormaRequest req) {
        String text = req.getUserMessage() == null ? "" : req.getUserMessage().replaceAll("\\s+", "");
        if (!WindowSealCompiler.matches(text)) return false;
        // Explicit seal phrases must not fall through to a full BUILD rebuild.
        if ("BUILD".equalsIgnoreCase(req.getPromptMode())) {
            req.setPromptMode("PATCH");
        }
        if (!(player.getEntityWorld() instanceof ServerWorld world)) return false;
        var manager = BuildExecutionService.getInstance().getMemoryManager();
        if (manager == null) return refuse(player, "建筑记忆尚未就绪，请等待建造完成后再修改。");

        var query = new BuildingTargetResolver.Query(
                world.getRegistryKey().getValue(),
                null,
                req.getSelectionMin(),
                req.getSelectionMax(),
                player.getBlockPos(),
                32);
        var service = new BuildingEditService(manager);
        var target = service.targets().resolve(query);
        if (!target.ok()) {
            return refuse(player, BuildingTargetResolver.refusalMessage(target).orElse("无法确定目标建筑。"));
        }
        var memory = manager.getMemory(target.instance().buildingId().toString());
        if (memory == null || memory.getBounds() == null) {
            return refuse(player, "指定建筑未登记在 Memory 中。");
        }
        var min = memory.getBounds().getMinPos();
        var max = memory.getBounds().getMaxPos();
        BlockPos playerPos = player.getBlockPos();
        if (req.getSelectionMin() == null && (playerPos.getX() < min.getX() - 12 || playerPos.getX() > max.getX() + 12
                || playerPos.getZ() < min.getZ() - 12 || playerPos.getZ() > max.getZ() + 12)) {
            return refuse(player, "请靠近或选择目标建筑，并等待建造完成后再封窗。");
        }
        long volume = ((long) max.getX() - min.getX() + 1)
                * (max.getY() - min.getY() + 1)
                * (max.getZ() - min.getZ() + 1);
        if (volume <= 0 || volume > 250000) return refuse(player, "目标建筑过大，请先选择明确的窗墙区域。");

        Map<BlockPos, BlockState> live = new HashMap<>();
        for (BlockPos p : BlockPos.iterate(min, max)) {
            if (!com.formacraft.common.world.WorldBuildBounds.isChunkReady(world, p)) {
                return refuse(player, "目标建筑尚未完整加载，请靠近后重试。");
            }
            live.put(p.toImmutable(), world.getBlockState(p));
        }
        BlockPos center = new BlockPos(
                (min.getX() + max.getX()) / 2,
                (min.getY() + max.getY()) / 2,
                (min.getZ() + max.getZ()) / 2);

        var prepared = service.prepareWindowSeal(
                query,
                live,
                center,
                world::isSkyVisible,
                text.contains("木格"),
                text.contains("天井") || text.contains("庭院"));
        if (!prepared.ok()) {
            String msg = prepared.refusals().isEmpty()
                    ? "未找到可确认的目标窗格及原墙材质；请靠近目标或选择窗墙区域后重试。"
                    : String.join("；", prepared.refusals());
            return refuse(player, msg);
        }

        PlayerProtectedZoneStorage.syncFromRequest(player, req);
        PlayerOutlineStorage.syncFromRequest(player, req);
        boolean restrict = "MODIFY_REGION".equalsIgnoreCase(req.getPromptMode());
        var ticket = PatchPreviewService.issuePreview(
                player,
                prepared.building().origin(),
                prepared.patches(),
                PlayerProtectedZoneStorage.get(player),
                PlayerOutlineStorage.get(player),
                restrict,
                req.getSelectionMin(),
                req.getSelectionMax(),
                true,
                null,
                PreviewEditKind.BUILDING_WINDOW_SEAL);
        if (ticket == null) return refuse(player, "封窗预览未能通过检查，请查看约束提示。");

        BuildingEditService.bindPendingToTicket(ticket.id(), player.getUuid(), ticket.expiresAtMillis(), prepared);
        if (BuildingEditPendingRegistry.get(ticket.id()) == null) {
            PatchPreviewService.cancel(player, ticket.id());
            return refuse(player, "封窗编辑上下文绑定失败，已取消预览。");
        }

        com.formacraft.FormacraftMod.LOGGER.info(
                "Window seal preview: player={} building={} cells={} ticket={}",
                player.getUuid(), prepared.building().buildingId(),
                prepared.patches().size(), ticket.id());
        ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                "封窗预览已就绪：仅替换现有窗格，使用相邻原白墙材质（确认后同步 Memory，可 Undo/Redo）。"));
        return true;
    }

    private static boolean refuse(ServerPlayerEntity player, String message) {
        com.formacraft.FormacraftMod.LOGGER.warn(
                "Window seal refused: player={} reason={}", player.getUuid(), message);
        ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(message));
        return true;
    }
}
