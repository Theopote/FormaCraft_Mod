package com.formacraft.server.network;

import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.server.edit.*;
import com.formacraft.server.build.BuildExecutionService;
import com.formacraft.server.patch.PatchPreviewService;
import com.formacraft.server.state.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Handles explicit window sealing before a model can return a replacement house. */
final class WindowSealPreview {
    private WindowSealPreview() {}
    static boolean tryHandle(ServerPlayerEntity player, FormaRequest req) {
        String text=req.getUserMessage()==null?"":req.getUserMessage().replaceAll("\\s+", "");
        if ("BUILD".equalsIgnoreCase(req.getPromptMode()) || !WindowSealCompiler.matches(text)) return false;
        if (!(player.getEntityWorld() instanceof ServerWorld world)) return false;
        var manager=BuildExecutionService.getInstance().getMemoryManager();
        if(manager==null) return refuse(player,"建筑记忆尚未就绪，请等待建造完成后再修改。");
        var target=new BuildingTargetResolver(manager).resolve(new BuildingTargetResolver.Query(
                world.getRegistryKey().getValue(),null,req.getSelectionMin(),req.getSelectionMax(),player.getBlockPos(),32));
        if(!target.ok()) return refuse(player,BuildingTargetResolver.refusalMessage(target).orElse("无法确定目标建筑。"));
        var memory=manager.getMemory(target.instance().buildingId().toString());
        var min=memory.getBounds().getMinPos(); var max=memory.getBounds().getMaxPos();
        BlockPos playerPos=player.getBlockPos();
        if(req.getSelectionMin()==null && (playerPos.getX()<min.getX()-4 || playerPos.getX()>max.getX()+4
                || playerPos.getZ()<min.getZ()-4 || playerPos.getZ()>max.getZ()+4))
            return refuse(player,"请靠近或选择目标建筑，并等待建造完成后再封窗。");
        long volume=((long)max.getX()-min.getX()+1)*(max.getY()-min.getY()+1)*(max.getZ()-min.getZ()+1);
        if(volume<=0 || volume>250000) return refuse(player,"目标建筑过大，请先选择明确的窗墙区域。");
        Map<BlockPos,BlockState> live=new HashMap<>();
        for(BlockPos p:BlockPos.iterate(min,max)) {
            if(!world.isChunkLoaded(p)) return refuse(player,"目标建筑尚未完整加载，请靠近后重试。");
            live.put(p.toImmutable(),world.getBlockState(p));
        }
        BlockPos center=new BlockPos((min.getX()+max.getX())/2,(min.getY()+max.getY())/2,(min.getZ()+max.getZ())/2);
        var patches=WindowSealCompiler.compile(live,target.instance().origin(),center,
                BuildingPartIndex.fromMemory(memory).roof().cells(),world::isSkyVisible,
                text.contains("木格"),text.contains("天井") || text.contains("庭院"));
        if(patches.isEmpty()) return refuse(player,"未找到可确认的目标窗格及原白墙材质；请靠近目标或选择窗墙区域后重试。");
        var ticket=PatchPreviewService.issuePreview(player,target.instance().origin(),patches,
                PlayerProtectedZoneStorage.get(player),PlayerOutlineStorage.get(player),
                "MODIFY_REGION".equalsIgnoreCase(req.getPromptMode()),req.getSelectionMin(),req.getSelectionMax());
        if(ticket!=null) {
            com.formacraft.FormacraftMod.LOGGER.info("Window seal preview: player={} building={} cells={} ticket={}",
                    player.getUuid(),target.instance().buildingId(),patches.size(),ticket.id());
            ServerPlayNetworking.send(player,new FormaCraftNetworking.ResponseBuildStatusPayload(
                "封窗预览已就绪：仅替换现有窗格，使用相邻原白墙材质（可 Undo/Redo）。"));
        }
        else return refuse(player,"封窗预览未能通过检查，请查看约束提示。");
        return true;
    }
    private static boolean refuse(ServerPlayerEntity player,String message) {
        com.formacraft.FormacraftMod.LOGGER.warn("Window seal refused: player={} reason={}",player.getUuid(),message);
        ServerPlayNetworking.send(player,new FormaCraftNetworking.ResponseBuildErrorPayload(message));return true;
    }
}
