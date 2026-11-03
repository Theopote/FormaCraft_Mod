package com.formacraft.server.network;

import com.formacraft.common.component.SelectedComponentPlacement;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.server.patch.PatchPreviewService;
import com.formacraft.server.preview.GenerationRequestContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.Direction;
import java.io.IOException;
import java.util.UUID;

/** Keeps receipt I/O and compilation off the game thread, then uses the authoritative ticket pipeline. */
final class CommunityPlacementHandler {
    private CommunityPlacementHandler() {}
    static void dispatch(FormaCraftNetworking.RequestPatchPreviewPayload payload,ServerPlayNetworking.Context context,Runnable legacy) {
        if(payload.communitySource()==null) { context.server().execute(legacy); return; }
        var player=context.player(); if(player==null) return;
        var permit=CommunityTaskService.admit(context.server(),player.getUuid()); if(permit==null) return;
        try { context.server().execute(() -> {
            try(permit) {
                if(context.server().getPlayerManager().getPlayer(player.getUuid())!=player) return;
                invalidate(context.server(),player.getUuid()); prepare(context.server(),player,payload);
            }
        }); } catch(RuntimeException stopped) { permit.close(); }
    }
    static void invalidate(MinecraftServer server,UUID owner) {
        try { CommunityTaskService.get(server).invalidateSelectedRequests(owner); } catch(IOException disabled) { /* No source service. */ }
    }
    private static void prepare(MinecraftServer server,ServerPlayerEntity player,FormaCraftNetworking.RequestPatchPreviewPayload payload) {
        if(!(player.getEntityWorld() instanceof ServerWorld world) || payload.origin()==null || player.getBlockPos().getSquaredDistance(payload.origin())>96.0*96.0) { error(player,"所选来源预览位置无效或过远"); return; }
        try {
            var coordinator=CommunityTaskService.get(server); var ref=payload.communitySource(); UUID owner=player.getUuid();
            CommunityTaskService.bridge(server).touch(owner,ref.sessionId());
            Direction facing=payload.facing()==null ? Direction.SOUTH : Direction.valueOf(payload.facing());
            Mirror mirror=payload.mirror()==null ? Mirror.NONE : Mirror.valueOf(payload.mirror());
            var root=server.getSavePath(WorldSavePath.ROOT); String dimension=world.getRegistryKey().getValue().toString(); long seed=payload.origin().asLong();
            var request=coordinator.useSelection(owner,ref.sessionId(),ref.taskId(),selected -> SelectedComponentPlacement.compile(
                    root,owner,selected,payload.componentId(),facing,mirror,payload.semanticSkin(),payload.semanticStyleId(),seed));
            request.result().whenComplete((compiled,failed) -> {
                try { server.execute(() -> {
                    try {
                        if(server.getPlayerManager().getPlayer(owner)!=player || player.getEntityWorld()!=world || CommunityTaskService.get(server)!=coordinator
                                || !coordinator.selectedRequestLatest(owner,ref.sessionId(),ref.taskId(),request.id())) return;
                        if(!coordinator.selectedRequestCurrent(owner,ref.sessionId(),ref.taskId(),request.id())) { error(player,"所选来源预览准备超时，请重试"); return; }
                        if(player.getBlockPos().getSquaredDistance(payload.origin())>96.0*96.0) { error(player,"预览位置已超出可操作距离"); return; }
                        if(failed!=null) { error(player,"所选来源不可用或预览准备失败，请重新选择来源；不会回退到其他来源"); return; }
                        GenerationRequestContext.begin(owner,dimension,payload.origin());
                        try {
                            var ticket=PatchPreviewService.issuePreview(player,payload.origin(),compiled.patches(),
                                    com.formacraft.server.state.PlayerProtectedZoneStorage.get(player),com.formacraft.server.state.PlayerOutlineStorage.get(player),
                                    payload.restrictToSelection(),payload.selectionMin(),payload.selectionMax(),true);
                            if(ticket!=null) com.formacraft.FormacraftMod.LOGGER.info("Selected component preview ticket={} owner={} receipt={} content={} manifest={}",
                                    ticket.id(),owner,compiled.receiptId(),compiled.contentRef(),compiled.manifestSha256());
                        } finally { GenerationRequestContext.clear(); }
                    } catch(IOException disabled) { /* Config changed while the task was in flight. */ }
                    catch(RuntimeException invalid) { error(player,"所选来源预览未发布，请查看服务器日志"); com.formacraft.FormacraftMod.LOGGER.warn("Selected component preview failed",invalid); }
                }); } catch(RuntimeException stopped) { /* Never deliver to a stopped server. */ }
            });
        } catch(IOException | IllegalArgumentException invalid) { error(player,"所选来源会话已失效或服务繁忙，请重新选择来源"); }
    }
    private static void error(ServerPlayerEntity player,String message) {
        if(ServerPlayNetworking.canSend(player,FormaCraftNetworking.ResponseBuildErrorPayload.ID))
            ServerPlayNetworking.send(player,new FormaCraftNetworking.ResponseBuildErrorPayload(message));
    }
}
