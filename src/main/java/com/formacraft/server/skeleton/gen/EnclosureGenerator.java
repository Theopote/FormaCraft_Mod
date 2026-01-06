package com.formacraft.server.skeleton.gen;

import com.formacraft.common.patch.BlockPatch;

import java.util.List;

/**
 * ENCLOSURE 生成器
 * 生成不规则围合骨架（v1：复用 PERIMETER_LOOP，后续可升级为支持缺口）
 */
public class EnclosureGenerator implements ISkeletonGenerator {
    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        // v1: 复用 PERIMETER_LOOP 逻辑，后续可升级为支持缺口
        PerimeterLoopGenerator delegate = new PerimeterLoopGenerator();
        return delegate.generate(ctx, plan);
    }
}

