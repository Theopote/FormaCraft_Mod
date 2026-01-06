package com.formacraft.server.skeleton.gen;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.FormacraftMod;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Random;

/**
 * Skeleton 建造服务
 * 
 * 将 ExecutableSkeletonPlan 转换为 BlockPatch 列表
 */
public class SkeletonBuildService {

    private final SkeletonGeneratorRegistry registry;

    public SkeletonBuildService() {
        this.registry = SkeletonGeneratorRegistry.createDefault();
    }

    public SkeletonBuildService(SkeletonGeneratorRegistry registry) {
        this.registry = registry;
    }

    /**
     * 构建骨架并生成 BlockPatch 列表
     * 
     * @param world 服务器世界
     * @param origin 原点位置
     * @param plan 可执行的骨架计划
     * @return BlockPatch 列表（相对 origin 的偏移）
     */
    public List<BlockPatch> build(ServerWorld world, BlockPos origin, ExecutableSkeletonPlan plan) {
        if (world == null || origin == null || plan == null) {
            FormacraftMod.LOGGER.warn("SkeletonBuildService.build: invalid parameters");
            return List.of();
        }

        GenerationContext ctx = new GenerationContext(world, origin, new Random(), 200_000);
        ISkeletonGenerator gen = registry.get(plan.type);
        
        List<BlockPatch> patches = gen.generate(ctx, plan);
        FormacraftMod.LOGGER.info("SkeletonBuildService: generated {} patches for type {}", 
                patches.size(), plan.type);
        
        return patches;
    }

    /**
     * 获取注册表（用于自定义注册）
     */
    public SkeletonGeneratorRegistry getRegistry() {
        return registry;
    }
}

