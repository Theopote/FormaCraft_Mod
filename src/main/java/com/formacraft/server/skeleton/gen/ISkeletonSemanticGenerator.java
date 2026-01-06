package com.formacraft.server.skeleton.gen;

import com.formacraft.common.semantic.SemanticPlacementOp;

import java.util.List;

/**
 * Skeleton 语义生成器接口
 * 
 * 生成器输出 SemanticPlacementOp 而不是直接的 BlockPatch
 */
public interface ISkeletonSemanticGenerator {
    /**
     * 生成语义放置操作
     * 
     * @param ctx 生成上下文
     * @param plan 可执行的骨架计划
     * @return 语义放置操作列表
     */
    List<SemanticPlacementOp> generateSemantic(GenerationContext ctx, ExecutableSkeletonPlan plan);
}

