package com.formacraft.common.gen.impl;

import com.formacraft.common.gen.GeneratorContext;
import com.formacraft.common.gen.PaletteResolver;
import com.formacraft.common.gen.SkeletonGenerator;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.skeleton.SkeletonPlan;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * LinearPathRoadGenerator（直线道路生成器）
 * 
 * 功能：生成直线道路（含简单地形贴合）
 * 
 * 参数：
 * - width: 道路宽度（默认 3）
 * - length: 前进长度（默认 20）
 * - axis: 轴向（"X" 或 "Z"，默认 "Z"）
 */
public class LinearPathRoadGenerator implements SkeletonGenerator {

    @Override
    public List<BlockPatch> generate(BlockPos origin, SkeletonPlan plan, GeneratorContext ctx) {
        // params
        int width = plan.intParam("width", 3);          // road width
        int length = plan.intParam("length", 20);       // forward length
        String axis = plan.strParam("axis", "Z");       // X or Z

        PaletteResolver palette = ctx.palette;
        String surface = palette.pickBlockId("road.surface");
        String edge = palette.pickBlockId("road.edge");

        List<BlockPatch> out = new ArrayList<>();
        BlockPos a = plan.anchor != null ? plan.anchor : origin;

        for (int i = 0; i < length; i++) {
            int dx = axis.equalsIgnoreCase("X") ? i : 0;
            int dz = axis.equalsIgnoreCase("Z") ? i : 0;

            // 这里演示一个"轻贴地形"：取 worldHeight = strategy.sampleGroundY(x,z)
            int groundY = ctx.terrainStrategy.sampleGroundY(ctx.world, a.getX() + dx, a.getZ() + dz);
            int dy = groundY - origin.getY();

            for (int w = -width / 2; w <= width / 2; w++) {
                int rx = axis.equalsIgnoreCase("X") ? dx : w;
                int rz = axis.equalsIgnoreCase("Z") ? dz : w;

                boolean isEdge = (w == -width / 2 || w == width / 2);
                String block = isEdge ? edge : surface;

                out.add(new BlockPatch(
                        BlockPatch.PLACE,
                        (a.getX() + rx) - origin.getX(),
                        dy,
                        (a.getZ() + rz) - origin.getZ(),
                        block
                ));
            }
        }
        return out;
    }
}

