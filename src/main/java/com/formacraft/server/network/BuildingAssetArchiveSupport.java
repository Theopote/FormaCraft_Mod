package com.formacraft.server.network;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.component.*;
import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import java.io.IOException;
import java.util.*;

/** Archive exactly the quality-checked final cells, not an earlier compiler candidate. */
final class BuildingAssetArchiveSupport {
    private BuildingAssetArchiveSupport() {}
    static List<BlockPatch> relativePatches(GeneratedStructure structure) {
        var origin = structure.getOrigin(); var patches = new ArrayList<BlockPatch>(structure.size());
        for (var block : structure.getBlocks()) {
            var pos = block.getPos().subtract(origin);
            patches.add(new BlockPatch(BlockPatch.PLACE,pos.getX(),pos.getY(),pos.getZ(),BlockStateStringUtil.fromState(block.getTargetState())));
        }
        return List.copyOf(patches);
    }
    static String save(ServerPlayerEntity player,ServerWorld world,LlmPlan plan,GeneratedStructure structure,BuildingAssetCapture.Data assets) throws IOException {
        if (assets == null || assets.uses().isEmpty()) return null;
        var origin = structure.getOrigin();
        try {
            return BuildingAssetLocks.save(world.getServer().getSavePath(WorldSavePath.ROOT),player.getUuid(),world.getRegistryKey().getValue().toString(),
                    world.getSeed(),origin.getX(),origin.getY(),origin.getZ(),JsonUtil.toJson(plan),assets,relativePatches(structure));
        } catch (IOException invalid) { throw new IOException("建筑构件锁保存失败，预览未发布；请查看服务端日志",invalid); }
    }
}
