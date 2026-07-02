package com.formacraft.common.skeleton;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 骨架执行门面（common 侧契约）。
 * <p>
 * Phase 1：common 编译器通过此接口调用骨架生成，避免直接依赖 {@code server.skeleton.gen}。
 * 服务端在 mod 初始化时注册 {@link com.formacraft.server.skeleton.gen.SkeletonBuildService} 实现。
 */
public interface SkeletonExecutor {

    /**
     * 将可执行骨架计划转换为 BlockPatch（相对 origin）。
     */
    List<BlockPatch> build(ServerWorld world, BlockPos origin, ExecutableSkeletonPlan plan);

    /**
     * 带调色板 / 风格配置的构建。
     */
    List<BlockPatch> build(ServerWorld world, BlockPos origin, ExecutableSkeletonPlan plan, String paletteId);
}
