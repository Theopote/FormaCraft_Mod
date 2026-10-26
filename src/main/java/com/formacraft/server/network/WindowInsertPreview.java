package com.formacraft.server.network;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.server.build.BuildExecutionService;
import com.formacraft.server.edit.*;
import com.formacraft.server.patch.PatchPreviewService;
import com.formacraft.server.state.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** The live building, not model slot offsets, owns a window's attachment location. */
final class WindowInsertPreview {
    static boolean tryHandle(ServerPlayerEntity player,FormaRequest req) {
        if("BUILD".equalsIgnoreCase(req.getPromptMode()) || !WindowInsertCompiler.matches(req.getUserMessage()))return false;
        if(!(player.getEntityWorld() instanceof ServerWorld world))return false;
        var manager=BuildExecutionService.getInstance().getMemoryManager();
        if(manager==null)return refuse(player,"建筑记忆未就绪，无法定位原墙面。");
        var target=new BuildingTargetResolver(manager).resolve(new BuildingTargetResolver.Query(world.getRegistryKey().getValue(),null,
                req.getSelectionMin(),req.getSelectionMax(),req.getPlayerPos()!=null?req.getPlayerPos():player.getBlockPos(),32));
        if(!target.ok())return refuse(player,BuildingTargetResolver.refusalMessage(target).orElse("请选择原建筑。"));
        var memory=manager.getMemory(target.instance().buildingId().toString());
        BlockPos min=memory.getBounds().getMinPos(),max=memory.getBounds().getMaxPos();
        long volume=((long)max.getX()-min.getX()+1)*(max.getY()-min.getY()+1)*(max.getZ()-min.getZ()+1);
        if(volume<1 || volume>250000)return refuse(player,"请缩小目标范围。");
        var live=new HashMap<BlockPos,BlockState>();
        for(BlockPos p:BlockPos.iterate(min,max)) {
            if(!world.isChunkLoaded(p))return refuse(player,"建筑尚未完整加载，请靠近后重试。");
            live.put(p.toImmutable(),world.getBlockState(p));
        }
        var patches=WindowInsertCompiler.compile(live,target.instance().origin(),BuildingPartIndex.fromMemory(memory).roof().cells(),
                WindowInsertCompiler.size(req.getUserMessage(),"宽"),WindowInsertCompiler.size(req.getUserMessage(),"高"));
        if(patches.isEmpty())return refuse(player,"未能唯一确认正门及右侧完整墙面，请选择目标建筑后重试；已拒绝在地面放窗。");
        var ticket=PatchPreviewService.issuePreview(player,target.instance().origin(),patches,PlayerProtectedZoneStorage.get(player),
                PlayerOutlineStorage.get(player),"MODIFY_REGION".equalsIgnoreCase(req.getPromptMode()),req.getSelectionMin(),req.getSelectionMax());
        if(ticket==null)return refuse(player,"墙面开窗预览未通过约束检查。");
        com.formacraft.FormacraftMod.LOGGER.info("Window insert preview: building={} origin={} cells={}",target.instance().buildingId(),target.instance().origin(),patches.size());
        ServerPlayNetworking.send(player,new FormaCraftNetworking.ResponseBuildStatusPayload("开窗预览已就绪：已根据原建筑正门和现场墙面定位。"));return true;
    }
    private static boolean refuse(ServerPlayerEntity player,String text) {
        ServerPlayNetworking.send(player,new FormaCraftNetworking.ResponseBuildErrorPayload(text));return true;
    }
}
