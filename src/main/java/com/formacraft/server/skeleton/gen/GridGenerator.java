package com.formacraft.server.skeleton.gen;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * GRID 生成器
 * 
 * v1：生成一个网格点阵/线网骨架
 */
public class GridGenerator implements ISkeletonGenerator {

    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        int w = Math.max(6, plan.get("width", 24));
        int d = Math.max(6, plan.get("depth", 24));
        int step = Math.max(2, plan.get("step", 4));
        String block = plan.get("block", "minecraft:lime_concrete");

        List<BlockPatch> patches = new ArrayList<>();
        BlockPos o = ctx.origin;

        // 画格线（x方向 + z方向）
        for (int x = 0; x <= w; x += step) {
            for (int z = 0; z <= d; z++) {
                BlockPos relative = new BlockPos(x, 0, z);
                patches.add(new BlockPatch(BlockPatch.PLACE, relative.getX(), relative.getY(), relative.getZ(), block));
                if (patches.size() >= ctx.maxOps) return patches;
            }
        }
        for (int z = 0; z <= d; z += step) {
            for (int x = 0; x <= w; x++) {
                BlockPos relative = new BlockPos(x, 0, z);
                patches.add(new BlockPatch(BlockPatch.PLACE, relative.getX(), relative.getY(), relative.getZ(), block));
                if (patches.size() >= ctx.maxOps) return patches;
            }
        }
        return patches;
    }
}

