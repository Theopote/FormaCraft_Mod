package com.formacraft.server.skeleton.gen;

import com.formacraft.common.skeleton.ExecutableSkeletonPlan;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.util.FacingUtil;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

import java.util.ArrayList;
import java.util.List;

/**
 * LINEAR_PATH 生成器（完整版）
 * 
 * 支持：
 * - 朝向（facing）
 * - 宽度（横向扩展）
 * - 顺地形/贴地生成（terrain conform）
 * - 高度策略（FLAT/FOLLOW_TERRAIN/STEP_UP/SLOPE）
 * 
 * 用途：
 * - 道路
 * - 城墙
 * - 桥
 * - 中轴建筑
 * - 建筑群主轴
 */
public class LinearPathGenerator implements ISkeletonGenerator {

    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        var layout = LinearPathLayout.create(ctx, plan, false);
        String block = plan.get("block", "minecraft:white_concrete");
        Vec3i right = FacingUtil.right(plan.facing);
        List<BlockPatch> patches = new ArrayList<>();
        for (var row : layout.rows()) {
            for (int column = 0; column < layout.width(); column++) {
                int offset = layout.start() + column;
                BlockPos pos = row.center().add(right.getX() * offset, 0, right.getZ() * offset);
                BlockPos relative = pos.subtract(ctx.origin);
                patches.add(new BlockPatch(BlockPatch.PLACE, relative.getX(), relative.getY(), relative.getZ(), block));
            }
        }
        return patches;
    }
}
