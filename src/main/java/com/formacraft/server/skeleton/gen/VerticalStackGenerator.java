package com.formacraft.server.skeleton.gen;

import com.formacraft.common.skeleton.ExecutableSkeletonPlan;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * VERTICAL_STACK 生成器
 * 
 * v1：垂直柱/筒，后面可升级为"层叠体块"
 */
public class VerticalStackGenerator implements ISkeletonGenerator {
    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        int height = Math.max(4, plan.get("height", 16));
        String block = plan.get("block", "minecraft:cyan_concrete");

        List<BlockPatch> patches = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            BlockPos relative = new BlockPos(0, y, 0);
            patches.add(new BlockPatch(BlockPatch.PLACE, relative.getX(), relative.getY(), relative.getZ(), block));
            if (patches.size() >= ctx.maxOps) return patches;
        }
        return patches;
    }
}

