package com.formacraft.server.skeleton.gen;

import com.formacraft.common.skeleton.ExecutableSkeletonPlan;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.skeleton.gen.util.GenMath;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * VERTICAL_TAPER 生成器
 * 
 * v1：从大半径到小半径的"圈圈向上收缩"
 */
public class VerticalTaperGenerator implements ISkeletonGenerator {
    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        int height = Math.max(8, plan.get("height", 20));
        int r0 = Math.max(2, plan.get("radiusBase", 8));
        int r1 = Math.max(1, plan.get("radiusTop", 2));
        String block = plan.get("block", "minecraft:orange_concrete");

        List<BlockPatch> patches = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            double t = y / (double) Math.max(1, height - 1);
            int r = (int) Math.round(r0 + (r1 - r0) * t);
            BlockPos center = ctx.origin.up(y);
            for (BlockPos p : GenMath.circleXZ(center, r)) {
                BlockPos relative = p.subtract(ctx.origin);
                patches.add(new BlockPatch(BlockPatch.PLACE, relative.getX(), relative.getY(), relative.getZ(), block));
                if (patches.size() >= ctx.maxOps) return patches;
            }
        }
        return patches;
    }
}

