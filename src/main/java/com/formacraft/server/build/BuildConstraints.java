package com.formacraft.server.build;

import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.model.constraint.ProtectedZone;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Server-side immutable constraints snapshot (v1):
 * - selection: optional AABB clamp (inclusive)
 * - outline: optional footprint clamp (circle/polygon + Y range)
 * - protectedZones: optional list of AABBs that are always forbidden
 */
public final class BuildConstraints {
    public final BlockPos selectionMin; // normalized, nullable
    public final BlockPos selectionMax; // normalized, nullable
    public final OutlineShape outline;  // nullable
    public final List<ProtectedZone> protectedZones; // non-null
    /** 笔刷 AABB（normalized, nullable）：无 selection 时作为区域约束（Phase 9） */
    public final BlockPos brushMin;
    public final BlockPos brushMax;
    /** 路径节点（世界坐标, nullable）+ 走廊半径：仅保留走廊内的方块（Phase 9） */
    public final List<BlockPos> pathNodes;
    public final int pathRadius;

    public BuildConstraints(BlockPos selectionMin, BlockPos selectionMax, OutlineShape outline, List<ProtectedZone> protectedZones) {
        this(selectionMin, selectionMax, outline, protectedZones, null, null, null, 0);
    }

    public BuildConstraints(BlockPos selectionMin, BlockPos selectionMax, OutlineShape outline,
                            List<ProtectedZone> protectedZones,
                            BlockPos brushMin, BlockPos brushMax,
                            List<BlockPos> pathNodes, int pathRadius) {
        this.selectionMin = selectionMin;
        this.selectionMax = selectionMax;
        this.outline = outline;
        this.protectedZones = protectedZones != null ? protectedZones : List.of();
        this.brushMin = brushMin;
        this.brushMax = brushMax;
        this.pathNodes = pathNodes;
        this.pathRadius = pathRadius;
    }

    public boolean allow(BlockPos p) {
        if (p == null) return false;

        // 1) protected zones (strongest)
        if (!protectedZones.isEmpty()) {
            for (ProtectedZone z : protectedZones) {
                if (z != null && z.contains(p)) return false;
            }
        }

        // 2) selection clamp
        if (selectionMin != null && selectionMax != null) {
            if (p.getX() < selectionMin.getX() || p.getX() > selectionMax.getX()
                    || p.getY() < selectionMin.getY() || p.getY() > selectionMax.getY()
                    || p.getZ() < selectionMin.getZ() || p.getZ() > selectionMax.getZ()) {
                return false;
            }
        } else if (brushMin != null && brushMax != null) {
            // 2b) 无 selection 时，笔刷 AABB 作为硬约束区域
            if (p.getX() < brushMin.getX() || p.getX() > brushMax.getX()
                    || p.getY() < brushMin.getY() || p.getY() > brushMax.getY()
                    || p.getZ() < brushMin.getZ() || p.getZ() > brushMax.getZ()) {
                return false;
            }
        }

        // 3) outline clamp
        if (outline != null) {
            if (!insideOutline(p, outline)) return false;
        }

        // 4) path corridor clamp（水平距离折线 <= radius）
        if (pathNodes != null && pathNodes.size() >= 2 && pathRadius > 0) {
            if (!insideCorridor(p, pathNodes, pathRadius)) return false;
        }

        return true;
    }

    /** 点到折线（仅 XZ 平面）最短距离是否在走廊半径内。 */
    private static boolean insideCorridor(BlockPos p, List<BlockPos> nodes, int radius) {
        double px = p.getX() + 0.5;
        double pz = p.getZ() + 0.5;
        double r2 = (double) radius * radius;
        for (int i = 0; i + 1 < nodes.size(); i++) {
            BlockPos a = nodes.get(i);
            BlockPos b = nodes.get(i + 1);
            if (a == null || b == null) continue;
            double d2 = distToSegmentSqXZ(px, pz,
                    a.getX() + 0.5, a.getZ() + 0.5,
                    b.getX() + 0.5, b.getZ() + 0.5);
            if (d2 <= r2) return true;
        }
        return false;
    }

    private static double distToSegmentSqXZ(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lenSq = dx * dx + dz * dz;
        double t = lenSq <= 1e-9 ? 0.0 : ((px - ax) * dx + (pz - az) * dz) / lenSq;
        if (t < 0.0) t = 0.0;
        else if (t > 1.0) t = 1.0;
        double cx = ax + t * dx;
        double cz = az + t * dz;
        double ex = px - cx;
        double ez = pz - cz;
        return ex * ex + ez * ez;
    }

    private static boolean insideOutline(BlockPos p, OutlineShape s) {
        // height range
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

    // Keep identical to client OutlineRule for consistent behavior.
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


