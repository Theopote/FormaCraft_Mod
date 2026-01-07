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
 * PolylineRoadGenerator（折线路径道路生成器）
 * 
 * 功能：生成折线路径道路（✅ PathTool 必接）
 * 
 * 这就是你要求的：路径工具可用于道路、沿路径布局、长城等。
 * 
 * 参数：
 * - width: 道路宽度（默认 3）
 * - step: 采样步长（默认 1）
 */
public class PolylineRoadGenerator implements SkeletonGenerator {

    @Override
    public List<BlockPatch> generate(BlockPos origin, SkeletonPlan plan, GeneratorContext ctx) {
        int width = plan.intParam("width", 3);
        int step = Math.max(1, plan.intParam("step", 1)); // sampling step

        // 优先：plan.points；否则从 PathTool 取
        List<BlockPos> pts = (plan.points != null && !plan.points.isEmpty())
                ? plan.points
                : (ctx.pathTool != null ? ctx.pathTool.getNodes() : List.of());

        if (pts.size() < 2) {
            return List.of();
        }

        PaletteResolver palette = ctx.palette;
        String surface = palette.pickBlockId("road.surface");
        String edge = palette.pickBlockId("road.edge");

        List<BlockPatch> out = new ArrayList<>();

        for (int i = 0; i < pts.size() - 1; i++) {
            BlockPos p0 = pts.get(i);
            BlockPos p1 = pts.get(i + 1);

            // 线段采样（DDA）
            int dx = p1.getX() - p0.getX();
            int dz = p1.getZ() - p0.getZ();
            int n = Math.max(Math.abs(dx), Math.abs(dz));
            if (n == 0) continue;

            for (int t = 0; t <= n; t += step) {
                int x = p0.getX() + (int) Math.round(dx * (t / (double) n));
                int z = p0.getZ() + (int) Math.round(dz * (t / (double) n));

                int groundY = ctx.terrainStrategy.sampleGroundY(ctx.world, x, z);
                int dy = groundY - origin.getY();

                for (int w = -width / 2; w <= width / 2; w++) {
                    // 简单法线近似：横向扩展在 X 方向（你后续可换成真实法线）
                    int xx = x + w;
                    int zz = z;

                    boolean isEdge = (w == -width / 2 || w == width / 2);
                    String block = isEdge ? edge : surface;

                    out.add(new BlockPatch(
                            BlockPatch.PLACE,
                            xx - origin.getX(),
                            dy,
                            zz - origin.getZ(),
                            block
                    ));
                }
            }
        }

        return out;
    }
}

