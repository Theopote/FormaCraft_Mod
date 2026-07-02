package com.formacraft.server.skeleton.gen;

import com.formacraft.common.skeleton.ExecutableSkeletonPlan;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * TERRACED 生成器
 * 生成台地式骨架（v1：简化版，生成多个高度平台）
 */
public class TerracedGenerator implements ISkeletonGenerator {
    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        // params:
        // - levels: int (台地层级数)
        // - width: int (每层宽度)
        // - depth: int (每层深度)
        // - stepHeight: int (每层高度差)
        // - block: string
        int levels = Math.max(2, plan.get("levels", 4));
        int width = Math.max(4, plan.get("width", 12));
        int depth = Math.max(4, plan.get("depth", 12));
        int stepHeight = Math.max(1, plan.get("stepHeight", 2));
        String block = plan.get("block", "minecraft:green_concrete");

        List<BlockPatch> patches = new ArrayList<>();
        BlockPos o = ctx.origin;

        // 生成多个台地层级
        for (int level = 0; level < levels; level++) {
            int y = level * stepHeight;
            int w = width - level * 2; // 每层逐渐缩小
            int d = depth - level * 2;
            if (w < 2 || d < 2) break;

            // 画台地边界
            for (int x = 0; x <= w; x++) {
                patches.add(new BlockPatch(BlockPatch.PLACE, x, y, 0, block));
                if (patches.size() >= ctx.maxOps) return patches;
                patches.add(new BlockPatch(BlockPatch.PLACE, x, y, d, block));
                if (patches.size() >= ctx.maxOps) return patches;
            }
            for (int z = 0; z <= d; z++) {
                patches.add(new BlockPatch(BlockPatch.PLACE, 0, y, z, block));
                if (patches.size() >= ctx.maxOps) return patches;
                patches.add(new BlockPatch(BlockPatch.PLACE, w, y, z, block));
                if (patches.size() >= ctx.maxOps) return patches;
            }
        }

        return patches;
    }
}

