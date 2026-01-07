package com.formacraft.server.skeleton.gen.path;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.skeleton.PathSkeleton;
import com.formacraft.common.terrain.TerrainPolicy;
import com.formacraft.common.terrain.TerrainStrategy;
import com.formacraft.server.skeleton.gen.GenerationContext;
import com.formacraft.server.skeleton.gen.ISkeletonGenerator;
import com.formacraft.server.skeleton.gen.ExecutableSkeletonPlan;

import java.util.List;

/**
 * PathSkeletonGenerator（路径骨架生成器）
 * 
 * 根据 PathSkeleton 和 PathIntent 选择具体的生成器实现
 */
public final class PathSkeletonGenerator implements ISkeletonGenerator {

    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        if (ctx == null || plan == null) {
            return List.of();
        }

        // 从 plan 中获取 PathSkeleton
        PathSkeleton pathSkeleton = (PathSkeleton) plan.get("pathSkeleton", null);
        if (pathSkeleton == null || !pathSkeleton.isValid()) {
            return List.of();
        }

        // 从 plan 中获取 TerrainPolicy
        TerrainPolicy terrainPolicy = (TerrainPolicy) plan.get("terrainPolicy", null);
        if (terrainPolicy == null) {
            // 默认策略：ADAPTIVE
            terrainPolicy = com.formacraft.common.terrain.TerrainPolicy.builder()
                    .strategy(TerrainStrategy.ADAPTIVE)
                    .scope(com.formacraft.common.terrain.TerrainPolicy.Scope.PATH)
                    .build();
        }

        // 根据 PathIntent 选择具体生成器
        ISkeletonGenerator generator = switch (pathSkeleton.intent) {
            case ROAD -> new RoadPathGenerator();
            case WALL -> new WallAlongPathGenerator();
            case BRIDGE -> new BridgePathGenerator();
            case ALONG_PATH_BUILDING -> new LinearBuildingPathGenerator();
            case GENERIC -> new GenericPathGenerator();
        };

        // 将 PathSkeleton 和 TerrainPolicy 传递给生成器
        ExecutableSkeletonPlan pathPlan = new ExecutableSkeletonPlan(plan.type)
                .put("pathSkeleton", pathSkeleton)
                .put("terrainPolicy", terrainPolicy);
        
        // 复制其他参数
        for (var entry : plan.params.entrySet()) {
            if (!"pathSkeleton".equals(entry.getKey()) && !"terrainPolicy".equals(entry.getKey())) {
                pathPlan.put(entry.getKey(), entry.getValue());
            }
        }

        return generator.generate(ctx, pathPlan);
    }
}

