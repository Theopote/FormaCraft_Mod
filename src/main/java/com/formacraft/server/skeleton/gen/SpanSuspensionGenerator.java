package com.formacraft.server.skeleton.gen;

import com.formacraft.common.skeleton.ExecutableSkeletonPlan;
import com.formacraft.common.skeleton.SkeletonParamParsers;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.skeleton.gen.util.GenMath;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SPAN_SUSPENSION 生成器
 * 
 * v1：做一个简单的"跨越"骨架：两端塔 + 一条桥面线
 * 以后可升级：悬链线 cable、索塔、桥面桁架等
 */
public class SpanSuspensionGenerator implements ISkeletonGenerator {

    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        // params:
        // - end: {dx,dy,dz}
        // - deckBlock, towerBlock
        // - towerHeight
        String deck = plan.get("deckBlock", "minecraft:light_gray_concrete");
        String tower = plan.get("towerBlock", "minecraft:gray_concrete");
        int towerH = Math.max(6, plan.get("towerHeight", 12));

        Object endObj = plan.params.get("end");
        if (!(endObj instanceof Map<?,?> m)) return List.of();

        int dx = SkeletonParamParsers.intValue(m.get("dx"), 0);
        int dy = SkeletonParamParsers.intValue(m.get("dy"), 0);
        int dz = SkeletonParamParsers.intValue(m.get("dz"), 24);

        BlockPos a = ctx.origin;
        BlockPos b = ctx.origin.add(dx, dy, dz);

        List<BlockPatch> patches = new ArrayList<>();

        // towers (simple columns)
        for (int i = 0; i < towerH; i++) {
            BlockPos relA = a.up(i).subtract(ctx.origin);
            BlockPos relB = b.up(i).subtract(ctx.origin);
            patches.add(new BlockPatch(BlockPatch.PLACE, relA.getX(), relA.getY(), relA.getZ(), tower));
            if (patches.size() >= ctx.maxOps) return patches;
            patches.add(new BlockPatch(BlockPatch.PLACE, relB.getX(), relB.getY(), relB.getZ(), tower));
            if (patches.size() >= ctx.maxOps) return patches;
        }

        // deck line
        for (BlockPos p : GenMath.line(a.up(2), b.up(2))) {
            BlockPos relative = p.subtract(ctx.origin);
            patches.add(new BlockPatch(BlockPatch.PLACE, relative.getX(), relative.getY(), relative.getZ(), deck));
            if (patches.size() >= ctx.maxOps) return patches;
        }

        return patches;
    }

}

