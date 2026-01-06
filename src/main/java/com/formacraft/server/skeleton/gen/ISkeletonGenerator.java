package com.formacraft.server.skeleton.gen;

import com.formacraft.common.patch.BlockPatch;

import java.util.List;

/**
 * Skeleton -> BlockPatch 的生成器接口
 * 
 * 每个 SkeletonType 对应一个 Generator 实现，
 * 负责将 ExecutableSkeletonPlan 转换为 BlockPatch 列表。
 */
public interface ISkeletonGenerator {
    /**
     * 生成 BlockPatch 列表
     * 
     * @param ctx 生成上下文（世界、原点、随机数、预算等）
     * @param plan 可执行的骨架计划
     * @return BlockPatch 列表（相对 origin 的偏移）
     */
    List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan);
}

