package com.formacraft.client.patch.filter.rules;

import com.formacraft.common.buildcontext.BuildContext;
import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchRule;
import com.formacraft.common.patch.filter.PatchRuleContext;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 轮廓规则：禁止在 footprint/outlines 外放置或修改方块。
 */
public class OutlineRule implements PatchRule {
    private final BuildContext bc;

    public OutlineRule(BuildContext bc) {
        this.bc = bc;
    }

    @Override
    public boolean allow(BlockPatch patch, PatchRuleContext ctx) {
        if (patch == null || ctx == null) return false;
        OutlineShape s = (bc != null) ? bc.outline : null;
        if (s == null) return true;

        BlockPos p = ctx.resolve(patch);
        // 高度范围约束（如果 shape 有）
        int y = p.getY();
        if (y < s.minY() || y > s.maxY()) return false;

        if ("circle".equalsIgnoreCase(s.shapeType()) && s.center() != null) {
            int dx = p.getX() - s.center().getX();
            int dz = p.getZ() - s.center().getZ();
            return (dx * dx + dz * dz) <= (s.radius() * s.radius());
        }

        List<BlockPos> poly = s.vertices();
        if (poly == null || poly.size() < 3) return true;
        return pointInPolygonXZ(p.getX() + 0.5, p.getZ() + 0.5, poly);
    }

    @Override
    public String reason() {
        return "Patch outside outline area";
    }

    /**
     * 射线法判断点是否在多边形内（XZ 平面）。
     */
    private static boolean pointInPolygonXZ(double x, double z, List<BlockPos> poly) {
        boolean inside = false;
        int n = poly.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = poly.get(i).getX() + 0.5;
            double zi = poly.get(i).getZ() + 0.5;
            double xj = poly.get(j).getX() + 0.5;
            double zj = poly.get(j).getZ() + 0.5;

            boolean intersect = ((zi > z) != (zj > z)) &&
                    (x < (xj - xi) * (z - zi) / (zj - zi + 0.0) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }
}


