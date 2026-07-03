package com.formacraft.common.tool;

import com.formacraft.common.buildcontext.OutlineShape;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 轮廓几何判断（与 BuildConstraints / OutlineRule 行为一致）。
 */
public final class OutlineGeometry {
    private OutlineGeometry() {}

    public static boolean contains(OutlineShape shape, BlockPos pos) {
        if (shape == null || pos == null) {
            return false;
        }

        int y = pos.getY();
        if (y < shape.minY() || y > shape.maxY()) {
            return false;
        }

        if ("circle".equalsIgnoreCase(shape.shapeType()) && shape.center() != null) {
            int dx = pos.getX() - shape.center().getX();
            int dz = pos.getZ() - shape.center().getZ();
            return (dx * dx + dz * dz) <= (shape.radius() * shape.radius());
        }

        List<BlockPos> points = shape.vertices();
        if (points == null || points.size() < 3) {
            return false;
        }

        return pointInPolygonXZ(pos.getX() + 0.5, pos.getZ() + 0.5, points);
    }

    private static boolean pointInPolygonXZ(double x, double z, List<BlockPos> poly) {
        boolean inside = false;
        int n = poly.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = poly.get(i).getX() + 0.5;
            double zi = poly.get(i).getZ() + 0.5;
            double xj = poly.get(j).getX() + 0.5;
            double zj = poly.get(j).getZ() + 0.5;

            boolean intersect = ((zi > z) != (zj > z))
                    && (x < (xj - xi) * (z - zi) / (zj - zi + 0.0) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }
}
