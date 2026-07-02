package com.formacraft.server.skeleton.gen.path;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.skeleton.PathSkeleton;
import com.formacraft.server.skeleton.gen.GenerationContext;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * BridgePathGenerator（桥梁路径生成器）
 */
public class BridgePathGenerator extends BasePathGenerator implements com.formacraft.server.skeleton.gen.ISkeletonGenerator {

    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        PathSkeleton pathSkeleton = (PathSkeleton) plan.get("pathSkeleton", null);
        com.formacraft.common.terrain.TerrainPolicy terrainPolicy = 
            (com.formacraft.common.terrain.TerrainPolicy) plan.get("terrainPolicy", null);
        
        if (pathSkeleton == null || !pathSkeleton.isValid() || terrainPolicy == null) {
            return List.of();
        }

        String blockId = plan.get("block", "minecraft:oak_planks");
        int bridgeHeight = plan.get("height", 3);

        List<BlockPatch> patches = new ArrayList<>();

        // 沿路径生成桥梁（保持水平）
        if (pathSkeleton.nodes.size() >= 2) {
            // 计算平均高度
            int totalY = 0;
            for (BlockPos node : pathSkeleton.nodes) {
                totalY += computeTargetY(ctx, node, terrainPolicy);
            }
            int avgY = totalY / pathSkeleton.nodes.size() + bridgeHeight;

            // 生成桥梁平台
            for (BlockPos node : pathSkeleton.nodes) {
                BlockPos pos = new BlockPos(node.getX(), avgY, node.getZ());
                BlockPos relative = pos.subtract(ctx.origin);

                patches.add(new BlockPatch(BlockPatch.PLACE,
                        relative.getX(), relative.getY(), relative.getZ(), blockId));

                if (patches.size() >= ctx.maxOps) return patches;
            }
        }

        return patches;
    }
}

