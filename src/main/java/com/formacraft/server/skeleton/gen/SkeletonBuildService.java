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
 * 
 * 注意：此服务使用新的语义系统（Semantic → Geometry → Palette → BlockPatch）
 * 如果某个 SkeletonType 没有语义生成器，会回退到旧的直接生成器
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
     * 优先使用新的语义系统（SkeletonBuildPipeline），
     * 如果没有语义生成器，回退到旧的直接生成器
     * 
     * @param world 服务器世界
     * @param origin 原点位置
     * @param plan 可执行的骨架计划
     * @return BlockPatch 列表（相对 origin 的偏移）
     */
    public List<BlockPatch> build(ServerWorld world, BlockPos origin, ExecutableSkeletonPlan plan) {
        return build(world, origin, plan, "DEFAULT");
    }

    /**
     * 构建骨架并生成 BlockPatch 列表（带调色板 ID）
     * 
     * @param world 服务器世界
     * @param origin 原点位置
     * @param plan 可执行的骨架计划
     * @param paletteId 调色板 ID（也用作 styleProfileId）
     * @return BlockPatch 列表（相对 origin 的偏移）
     */
    public List<BlockPatch> build(ServerWorld world, BlockPos origin, ExecutableSkeletonPlan plan, String paletteId) {
        if (world == null || origin == null || plan == null) {
            FormacraftMod.LOGGER.warn("SkeletonBuildService.build: invalid parameters");
            return List.of();
        }

        // 优先使用新的语义系统
        if (SkeletonSemanticRegistry.get(plan.type) != null) {
            GenerationContext ctx = new GenerationContext(world, origin, new Random(), 200_000);
            List<BlockPatch> patches = SkeletonBuildPipeline.buildSkeletonAsPatch(ctx, plan, paletteId, origin);
            FormacraftMod.LOGGER.info("SkeletonBuildService: generated {} patches for type {} (semantic system)", 
                    patches.size(), plan.type);
            return patches;
        }

        // 回退到旧的直接生成器
        GenerationContext ctx = new GenerationContext(world, origin, new Random(), 200_000);
        ISkeletonGenerator gen = registry.get(plan.type);
        
        List<BlockPatch> patches = gen.generate(ctx, plan);
        FormacraftMod.LOGGER.info("SkeletonBuildService: generated {} patches for type {} (legacy generator)", 
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

