package com.formacraft.server.skeleton.gen;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * COURTYARD 生成器
 * 生成中庭围合骨架（v1：简化版，生成一个矩形围合）
 */
public class CourtyardGenerator implements ISkeletonGenerator {
    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        // params:
        // - width: int
        // - depth: int
        // - block: string
        int width = Math.max(8, plan.get("width", 16));
        int depth = Math.max(8, plan.get("depth", 16));
        String block = plan.get("block", "minecraft:brown_concrete");

        List<BlockPatch> patches = new ArrayList<>();
        BlockPos o = ctx.origin;

        // 生成矩形围合（只画边界）
        for (int x = 0; x <= width; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 0, block));
            if (patches.size() >= ctx.maxOps) return patches;
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, depth, block));
            if (patches.size() >= ctx.maxOps) return patches;
        }
        for (int z = 0; z <= depth; z++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, 0, 0, z, block));
            if (patches.size() >= ctx.maxOps) return patches;
            patches.add(new BlockPatch(BlockPatch.PLACE, width, 0, z, block));
            if (patches.size() >= ctx.maxOps) return patches;
        }

        return patches;
    }
}

