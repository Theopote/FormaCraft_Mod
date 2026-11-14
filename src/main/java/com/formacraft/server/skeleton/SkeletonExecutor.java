package com.formacraft.server.skeleton;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 服务端骨架执行门面。
 * <p>
 * 服务端编译器通过此接口调用骨架生成，具体实现位于 {@code server.skeleton.gen}。
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
