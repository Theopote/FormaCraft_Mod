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

/** The live building, not model slot offsets, owns a window's attachment location. */
final class WindowInsertPreview {
    private WindowInsertPreview() {}

    static boolean tryHandle(ServerPlayerEntity player, FormaRequest req) {
        // Phrase match is specific enough to own the request even when Chat labels BUILD.
        // Otherwise LLM rebuilds foundations beside the host (in-game Round 65 regression).
        if (!WindowInsertCompiler.matches(req.getUserMessage())) {
            return false;
        }
        if ("BUILD".equalsIgnoreCase(req.getPromptMode())) {
            req.setPromptMode("PATCH");
        }
        if (!(player.getEntityWorld() instanceof ServerWorld world)) return false;
        var manager = BuildExecutionService.getInstance().getMemoryManager();
        if (manager == null) return refuse(player, "建筑记忆未就绪，无法定位原墙面。");

        var query = new BuildingTargetResolver.Query(
                world.getRegistryKey().getValue(),
                null,
                req.getSelectionMin(),
                req.getSelectionMax(),
                req.getPlayerPos() != null ? req.getPlayerPos() : player.getBlockPos(),
                32);
        var service = new BuildingEditService(manager);
        var target = service.targets().resolve(query);
        if (!target.ok()) {
            return refuse(player, BuildingTargetResolver.refusalMessage(target).orElse("请选择原建筑。"));
        }
        var memory = manager.getMemory(target.instance().buildingId().toString());
        if (memory == null || memory.getBounds() == null) {
            return refuse(player, "指定建筑未登记在 Memory 中。");
        }
        BlockPos min = memory.getBounds().getMinPos();
        BlockPos max = memory.getBounds().getMaxPos();
        long volume = ((long) max.getX() - min.getX() + 1)
                * (max.getY() - min.getY() + 1)
                * (max.getZ() - min.getZ() + 1);
        if (volume < 1 || volume > 250000) return refuse(player, "请缩小目标范围。");

        var live = new HashMap<BlockPos, BlockState>();
        for (BlockPos p : BlockPos.iterate(min, max)) {
            if (!com.formacraft.common.world.WorldBuildBounds.isChunkReady(world, p)) {
                return refuse(player, "建筑尚未完整加载，请靠近后重试。");
            }
            live.put(p.toImmutable(), world.getBlockState(p));
        }

        var prepared = service.prepareWindowInsert(
                query,
                live,
                WindowInsertCompiler.size(req.getUserMessage(), "宽"),
                WindowInsertCompiler.size(req.getUserMessage(), "高"));
        if (!prepared.ok()) {
            String msg = prepared.refusals().isEmpty()
                    ? "正门右侧没有找到足够宽的完整墙面，或入口无法唯一确认。"
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
                PreviewEditKind.BUILDING_WINDOW_INSERT);
        if (ticket == null) return refuse(player, "墙面开窗预览未通过约束检查。");

        BuildingEditService.bindPendingToTicket(ticket.id(), player.getUuid(), ticket.expiresAtMillis(), prepared);
        if (BuildingEditPendingRegistry.get(ticket.id()) == null) {
            PatchPreviewService.cancel(player, ticket.id());
            return refuse(player, "开窗编辑上下文绑定失败，已取消预览。");
        }

        com.formacraft.FormacraftMod.LOGGER.info(
                "Window insert preview: building={} origin={} cells={} ticket={}",
                prepared.building().buildingId(), prepared.building().origin(),
                prepared.patches().size(), ticket.id());
        ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                "开窗预览已就绪：已根据原建筑正门和现场墙面定位（确认后同步 Memory）。"));
        return true;
    }

    private static boolean refuse(ServerPlayerEntity player, String text) {
        ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildErrorPayload(text));
        return true;
    }
}
