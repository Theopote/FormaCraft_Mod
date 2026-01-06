package com.formacraft.server.skeleton.gen;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.skeleton.gen.util.GenMath;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * RADIAL_RING 生成器
 * 生成闭合环形骨架
 */
public class RadialRingGenerator implements ISkeletonGenerator {

    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        // params:
        // - radius: int
        // - y: int (可选，相对 origin)
        // - block: string
        int radius = Math.max(2, plan.get("radius", 6));
        int y = plan.get("y", 0);
        String block = plan.get("block", "minecraft:purple_concrete");

        BlockPos center = ctx.origin.add(0, y, 0);

        List<BlockPatch> patches = new ArrayList<>();
        for (BlockPos p : GenMath.circleXZ(center, radius)) {
            BlockPos relative = p.subtract(ctx.origin);
            patches.add(new BlockPatch(BlockPatch.PLACE, relative.getX(), relative.getY(), relative.getZ(), block));
            if (patches.size() >= ctx.maxOps) return patches;
        }
        return patches;
    }
}

