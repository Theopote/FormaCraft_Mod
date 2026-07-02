package com.formacraft.server.skeleton.gen.path;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.skeleton.PathSkeleton;
import com.formacraft.server.skeleton.gen.GenerationContext;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * WallAlongPathGenerator（沿路径城墙生成器）
 * 
 * 用于生成长城、城墙等沿路径的防御结构
 */
public class WallAlongPathGenerator extends BasePathGenerator implements com.formacraft.server.skeleton.gen.ISkeletonGenerator {

    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        PathSkeleton pathSkeleton = (PathSkeleton) plan.get("pathSkeleton", null);
        com.formacraft.common.terrain.TerrainPolicy terrainPolicy = 
            (com.formacraft.common.terrain.TerrainPolicy) plan.get("terrainPolicy", null);
        
        if (pathSkeleton == null || !pathSkeleton.isValid() || terrainPolicy == null) {
            return List.of();
        }

        String blockId = plan.get("block", "minecraft:stone_bricks");
        int wallHeight = plan.get("height", 5);

        List<BlockPatch> patches = new ArrayList<>();

        // 沿路径生成城墙
        for (BlockPos node : pathSkeleton.nodes) {
            int targetY = computeTargetY(ctx, node, terrainPolicy);

            // 生成城墙（垂直堆叠）
            for (int dy = 0; dy < wallHeight; dy++) {
                BlockPos pos = new BlockPos(node.getX(), targetY + dy, node.getZ());
                BlockPos relative = pos.subtract(ctx.origin);

                patches.add(new BlockPatch(BlockPatch.PLACE,
                        relative.getX(), relative.getY(), relative.getZ(), blockId));

                if (patches.size() >= ctx.maxOps) return patches;
            }
        }

        return patches;
    }
}

