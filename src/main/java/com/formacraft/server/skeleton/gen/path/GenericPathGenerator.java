package com.formacraft.server.skeleton.gen.path;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.skeleton.PathSkeleton;
import com.formacraft.server.skeleton.gen.GenerationContext;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;

import java.util.List;

/**
 * GenericPathGenerator（通用路径生成器）
 * 
 * 默认的路径生成器，用于未明确指定意图的路径
 */
public class GenericPathGenerator extends BasePathGenerator implements com.formacraft.server.skeleton.gen.ISkeletonGenerator {

    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        PathSkeleton pathSkeleton = (PathSkeleton) plan.get("pathSkeleton", null);
        com.formacraft.common.terrain.TerrainPolicy terrainPolicy = 
            (com.formacraft.common.terrain.TerrainPolicy) plan.get("terrainPolicy", null);
        
        if (pathSkeleton == null || !pathSkeleton.isValid() || terrainPolicy == null) {
            return List.of();
        }

        String blockId = plan.get("block", "minecraft:cobblestone");
        
        // 生成通用路径
        return generateCorridor(ctx, pathSkeleton.nodes, terrainPolicy, blockId);
    }
}

