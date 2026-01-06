package com.formacraft.server.skeleton.gen;

import com.formacraft.common.patch.BlockPatch;

import java.util.ArrayList;
import java.util.List;

/**
 * COMPOUND 生成器：递归组合
 * 
 * 递归调用子 skeleton 的 generator
 */
public class CompoundGenerator implements ISkeletonGenerator {

    private final SkeletonGeneratorRegistry registry;

    public CompoundGenerator(SkeletonGeneratorRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        List<BlockPatch> patches = new ArrayList<>();
        for (ExecutableSkeletonPlan child : plan.children) {
            if (child == null || child.type == null) continue;
            ISkeletonGenerator g = registry.get(child.type);
            List<BlockPatch> part = g.generate(ctx, child);
            for (BlockPatch patch : part) {
                patches.add(patch);
                if (patches.size() >= ctx.maxOps) return patches;
            }
        }
        return patches;
    }
}

