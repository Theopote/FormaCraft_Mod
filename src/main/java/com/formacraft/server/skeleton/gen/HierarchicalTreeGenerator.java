package com.formacraft.server.skeleton.gen;

import com.formacraft.common.patch.BlockPatch;

import java.util.List;

/**
 * HIERARCHICAL_TREE 生成器
 * 生成主从层级骨架（v1：复用 COMPOUND，后续可升级为明确的主从关系处理）
 */
public class HierarchicalTreeGenerator implements ISkeletonGenerator {
    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        // v1: 复用 COMPOUND 逻辑，后续可升级为明确的主从关系处理
        // 可以添加 root 和 branches 的特殊处理逻辑
        CompoundGenerator delegate = new CompoundGenerator(
            SkeletonGeneratorRegistry.createDefault()
        );
        return delegate.generate(ctx, plan);
    }
}

