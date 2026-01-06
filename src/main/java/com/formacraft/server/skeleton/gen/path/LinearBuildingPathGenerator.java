package com.formacraft.server.skeleton.gen.path;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.skeleton.PathSkeleton;
import com.formacraft.server.skeleton.gen.GenerationContext;
import com.formacraft.server.skeleton.gen.ExecutableSkeletonPlan;

import java.util.List;

/**
 * LinearBuildingPathGenerator（沿路径建筑生成器）
 * 
 * 用于生成沿路径的线性建筑（如沿街建筑）
 */
public class LinearBuildingPathGenerator extends BasePathGenerator implements com.formacraft.server.skeleton.gen.ISkeletonGenerator {

    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        // v1：简化实现，后续可以扩展为完整的沿街建筑生成
        PathSkeleton pathSkeleton = (PathSkeleton) plan.get("pathSkeleton", null);
        com.formacraft.common.terrain.TerrainPolicy terrainPolicy = 
            (com.formacraft.common.terrain.TerrainPolicy) plan.get("terrainPolicy", null);
        
        if (pathSkeleton == null || !pathSkeleton.isValid() || terrainPolicy == null) {
            return List.of();
        }

        String blockId = plan.get("block", "minecraft:stone_bricks");
        
        // 简化：生成基础路径
        return generateCorridor(ctx, pathSkeleton.nodes, terrainPolicy, blockId);
    }
}

