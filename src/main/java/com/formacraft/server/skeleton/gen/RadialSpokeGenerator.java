package com.formacraft.server.skeleton.gen;

import com.formacraft.common.skeleton.ExecutableSkeletonPlan;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.skeleton.gen.util.GenMath;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RADIAL_SPOKE 生成器
 * 生成中心辐射骨架
 */
public class RadialSpokeGenerator implements ISkeletonGenerator {
    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        // params:
        // - radius: int (辐射半径)
        // - spokes: int (辐射线数量，默认 8)
        // - y: int (可选，相对 origin)
        // - block: string
        int radius = Math.max(2, plan.get("radius", 10));
        int spokes = Math.max(4, plan.get("spokes", 8));
        int y = plan.get("y", 0);
        String block = plan.get("block", "minecraft:magenta_concrete");

        BlockPos center = ctx.origin.add(0, y, 0);
        List<BlockPatch> patches = new ArrayList<>();

        // 从中心向各个方向辐射
        for (int i = 0; i < spokes; i++) {
            double angle = (Math.PI * 2.0) * (i / (double) spokes);
            int endX = center.getX() + (int) Math.round(Math.cos(angle) * radius);
            int endZ = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
            BlockPos end = new BlockPos(endX, center.getY(), endZ);

            for (BlockPos p : GenMath.line(center, end)) {
                BlockPos relative = p.subtract(ctx.origin);
                patches.add(new BlockPatch(BlockPatch.PLACE, relative.getX(), relative.getY(), relative.getZ(), block));
                if (patches.size() >= ctx.maxOps) return patches;
            }
        }

        return patches;
    }
}

