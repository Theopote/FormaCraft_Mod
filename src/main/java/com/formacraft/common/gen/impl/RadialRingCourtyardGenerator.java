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
 * RadialRingCourtyardGenerator（环形院落生成器）
 * 
 * 功能：生成环形院落（圆形轮廓 → 立即能看到"城堡/土楼"味道）
 * 
 * 参数：
 * - radius: 半径（默认 10）
 * - wallHeight: 墙体高度（默认 6）
 * - thickness: 墙体厚度（默认 2）
 */
public class RadialRingCourtyardGenerator implements SkeletonGenerator {

    @Override
    public List<BlockPatch> generate(BlockPos origin, SkeletonPlan plan, GeneratorContext ctx) {
        int radius = plan.intParam("radius", 10);
        int wallHeight = plan.intParam("wallHeight", 6);
        int thickness = plan.intParam("thickness", 2);

        BlockPos c = plan.anchor != null ? plan.anchor : origin;

        PaletteResolver palette = ctx.palette;
        String wall = palette.pickBlockId("wall.base");
        String top = palette.pickBlockId("wall.top");

        List<BlockPatch> out = new ArrayList<>();

        // 画"环带"：r..r+thickness-1
        for (int rr = radius; rr < radius + thickness; rr++) {
            // Midpoint circle like raster
            int x = rr;
            int z = 0;
            int d = 1 - x;

            while (x >= z) {
                placeCircle8(out, origin, c, x, z, wall, wallHeight, ctx, top);
                z++;
                if (d < 0) {
                    d += 2 * z + 1;
                } else {
                    x--;
                    d += 2 * (z - x) + 1;
                }
            }
        }

        return out;
    }

    private void placeCircle8(
            List<BlockPatch> out,
            BlockPos origin,
            BlockPos c,
            int x, int z,
            String wall,
            int wallHeight,
            GeneratorContext ctx,
            String top
    ) {
        int[][] pts = new int[][]{
                { c.getX() + x, c.getZ() + z },
                { c.getX() + z, c.getZ() + x },
                { c.getX() - z, c.getZ() + x },
                { c.getX() - x, c.getZ() + z },
                { c.getX() - x, c.getZ() - z },
                { c.getX() - z, c.getZ() - x },
                { c.getX() + z, c.getZ() - x },
                { c.getX() + x, c.getZ() - z },
        };

        for (int[] p : pts) {
            int gx = p[0];
            int gz = p[1];
            int groundY = ctx.terrainStrategy.sampleGroundY(ctx.world, gx, gz);
            int baseDy = groundY - origin.getY();

            // 垂直墙体
            for (int h = 0; h < wallHeight; h++) {
                out.add(new BlockPatch(
                        BlockPatch.PLACE,
                        gx - origin.getX(),
                        baseDy + h,
                        gz - origin.getZ(),
                        wall
                ));
            }
            // 顶部一圈（可以做垛口/檐口）
            out.add(new BlockPatch(
                    BlockPatch.PLACE,
                    gx - origin.getX(),
                    baseDy + wallHeight,
                    gz - origin.getZ(),
                    top
            ));
        }
    }
}

