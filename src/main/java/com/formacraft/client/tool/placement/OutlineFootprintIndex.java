package com.formacraft.client.tool.placement;

import com.formacraft.client.tool.OutlineMode;
import com.formacraft.client.tool.OutlineTool;
import com.formacraft.common.component.placement.FacingDeriver;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * OutlineFootprintIndex（v1）：
 * - 基于 OutlineTool 的 footprint 边界判断 EDGE/CORNER
 * - 提供“沿边切线方向”（用于 FacingPolicy.ALONG_EDGE）
 *
 * 说明：
 * - 这是 Tool 层的几何索引，不是 AI Prompt 文本。
 * - 仅使用 X/Z（点按方块中心 0.5 计算）。
 */
public final class OutlineFootprintIndex {
    private OutlineFootprintIndex() {}

    /** 判定点离边界足够近的阈值（单位：方块）。 */
    public static final double EDGE_TOL = 0.90;
    /** 判定点离角点足够近的阈值（单位：方块）。 */
    public static final double CORNER_TOL = 0.90;

    public static boolean hasShape() {
        return OutlineTool.INSTANCE.hasShape() && OutlineTool.INSTANCE.getShape() != null;
    }

    public static OutlineTool.OutlineShape shape() {
        return OutlineTool.INSTANCE.getShape();
    }

    /**
     * 判断某个世界坐标点（按 block center）是否位于 footprint 内部。
     * <p>
     * v1：circle 用半径；polygon 用射线法。
     */
    public static boolean contains(BlockPos pos) {
        if (!hasShape() || pos == null) return false;
        return containsXZ(pos.getX() + 0.5, pos.getZ() + 0.5);
    }

    public static boolean containsXZ(double x, double z) {
        if (!hasShape()) return false;
        OutlineTool.OutlineShape s = shape();
        if (s == null) return false;
        if (s.mode() == OutlineMode.CIRCLE && s.center() != null) {
            double cx = s.center().getX() + 0.5;
            double cz = s.center().getZ() + 0.5;
            double dx = x - cx;
            double dz = z - cz;
            double r = Math.max(1, s.radius()) + 0.50; // 容差：按 block center
            return (dx * dx + dz * dz) <= (r * r);
        }
        List<BlockPos> pts = s.points();
        if (pts == null || pts.size() < 3) return false;
        return pointInPolygonXZ(x, z, pts);
    }

    /**
     * 对于墙面命中：推导“外法线”（如果 hitFace 是内侧，则会自动翻转）。
     * 返回 null 表示无法推导（例如没有 outline 或不水平）。
     */
    public static Direction inferWallOutwardNormal(BlockPos hitPos, Direction hitFace) {
        if (!hasShape() || hitPos == null || hitFace == null) return null;
        if (!hitFace.getAxis().isHorizontal()) return null;
        double px = hitPos.getX() + 0.5;
        double pz = hitPos.getZ() + 0.5;
        double fx = hitFace.getOffsetX() * 0.60;
        double fz = hitFace.getOffsetZ() * 0.60;

        boolean outForward = !containsXZ(px + fx, pz + fz);
        boolean outBackward = !containsXZ(px - fx, pz - fz);
        if (outForward && !outBackward) {
            // hitFace 指向 outside
            return hitFace;
        }
        if (!outForward && outBackward) {
            // hitFace 指向 inside
            return hitFace.getOpposite();
        }
        return hitFace; // 模糊：保持不变
    }

    /**
     * 对于墙面命中：判断“hitFace 这一侧”是否为 exterior。
     * 若无法判断，返回 null。
     */
    public static Boolean isWallFaceExterior(BlockPos hitPos, Direction hitFace) {
        if (!hasShape() || hitPos == null || hitFace == null) return null;
        if (!hitFace.getAxis().isHorizontal()) return null;
        double px = hitPos.getX() + 0.5;
        double pz = hitPos.getZ() + 0.5;
        double fx = hitFace.getOffsetX() * 0.60;
        double fz = hitFace.getOffsetZ() * 0.60;
        boolean outForward = !containsXZ(px + fx, pz + fz);
        boolean outBackward = !containsXZ(px - fx, pz - fz);
        if (outForward && !outBackward) return Boolean.TRUE;
        if (!outForward && outBackward) return Boolean.FALSE;
        return null;
    }

    public static boolean isNearEdge(BlockPos pos) {
        return isNearEdge(pos, EDGE_TOL);
    }

    public static boolean isNearCorner(BlockPos pos) {
        return isNearCorner(pos, CORNER_TOL);
    }

    public static Direction edgeTangentDirection(BlockPos pos) {
        if (!hasShape() || pos == null) return null;
        OutlineTool.OutlineShape s = OutlineTool.INSTANCE.getShape();
        if (s == null) return null;
        if (!withinYRange(s, pos)) return null;

        if (s.mode() == OutlineMode.CIRCLE && s.center() != null) {
            return circleTangent(s.center(), Math.max(1, s.radius()), pos);
        }

        List<BlockPos> pts = s.points();
        if (pts == null || pts.size() < 2) return null;
        Seg seg = nearestSegment(pts, pos);
        if (seg == null) return null;
        int dx = seg.bx - seg.ax;
        int dz = seg.bz - seg.az;
        return FacingDeriver.cardinalFromDelta(dx, dz);
    }

    public static boolean isNearEdge(BlockPos pos, double tol) {
        if (!hasShape() || pos == null) return false;
        OutlineTool.OutlineShape s = OutlineTool.INSTANCE.getShape();
        if (s == null) return false;
        if (!withinYRange(s, pos)) return false;

        if (s.mode() == OutlineMode.CIRCLE && s.center() != null) {
            return circleNearEdge(s.center(), Math.max(1, s.radius()), pos, tol);
        }

        List<BlockPos> pts = s.points();
        if (pts == null || pts.size() < 2) return false;
        double d2 = minDistSqToSegments(pts, pos);
        return d2 <= tol * tol;
    }

    public static boolean isNearCorner(BlockPos pos, double tol) {
        if (!hasShape() || pos == null) return false;
        OutlineTool.OutlineShape s = OutlineTool.INSTANCE.getShape();
        if (s == null) return false;
        if (!withinYRange(s, pos)) return false;

        if (s.mode() == OutlineMode.CIRCLE && s.center() != null) {
            return circleNearCorner8(s.center(), Math.max(1, s.radius()), pos, tol);
        }

        List<BlockPos> pts = s.points();
        if (pts == null || pts.isEmpty()) return false;
        double px = pos.getX() + 0.5;
        double pz = pos.getZ() + 0.5;
        double best = Double.POSITIVE_INFINITY;
        for (BlockPos p : pts) {
            if (p == null) continue;
            double vx = p.getX() + 0.5;
            double vz = p.getZ() + 0.5;
            double dx = px - vx;
            double dz = pz - vz;
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best <= tol * tol;
    }

    // ----------------- helpers -----------------

    private record Seg(int ax, int az, int bx, int bz) {}

    private static Seg nearestSegment(List<BlockPos> poly, BlockPos pos) {
        if (poly == null || poly.size() < 2 || pos == null) return null;
        double px = pos.getX() + 0.5;
        double pz = pos.getZ() + 0.5;
        double best = Double.POSITIVE_INFINITY;
        Seg bestSeg = null;
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            BlockPos a = poly.get(i);
            BlockPos b = poly.get((i + 1) % n);
            if (a == null || b == null) continue;
            double ax = a.getX() + 0.5;
            double az = a.getZ() + 0.5;
            double bx = b.getX() + 0.5;
            double bz = b.getZ() + 0.5;
            double d2 = distSqPointToSeg(px, pz, ax, az, bx, bz);
            if (d2 < best) {
                best = d2;
                bestSeg = new Seg(a.getX(), a.getZ(), b.getX(), b.getZ());
            }
        }
        return bestSeg;
    }

    private static double minDistSqToSegments(List<BlockPos> poly, BlockPos pos) {
        if (poly == null || poly.size() < 2 || pos == null) return Double.POSITIVE_INFINITY;
        double px = pos.getX() + 0.5;
        double pz = pos.getZ() + 0.5;
        double best = Double.POSITIVE_INFINITY;
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            BlockPos a = poly.get(i);
            BlockPos b = poly.get((i + 1) % n);
            if (a == null || b == null) continue;
            double ax = a.getX() + 0.5;
            double az = a.getZ() + 0.5;
            double bx = b.getX() + 0.5;
            double bz = b.getZ() + 0.5;
            best = Math.min(best, distSqPointToSeg(px, pz, ax, az, bx, bz));
        }
        return best;
    }

    private static double distSqPointToSeg(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax;
        double vz = bz - az;
        double wx = px - ax;
        double wz = pz - az;
        double vv = vx * vx + vz * vz;
        if (vv <= 1e-9) {
            double dx = px - ax;
            double dz = pz - az;
            return dx * dx + dz * dz;
        }
        double t = (wx * vx + wz * vz) / vv;
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        double cx = ax + t * vx;
        double cz = az + t * vz;
        double dx = px - cx;
        double dz = pz - cz;
        return dx * dx + dz * dz;
    }

    private static boolean circleNearEdge(BlockPos center, int r, BlockPos pos, double tol) {
        double cx = center.getX() + 0.5;
        double cz = center.getZ() + 0.5;
        double px = pos.getX() + 0.5;
        double pz = pos.getZ() + 0.5;
        double dx = px - cx;
        double dz = pz - cz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        return Math.abs(dist - r) <= tol;
    }

    private static boolean circleNearCorner8(BlockPos center, int r, BlockPos pos, double tol) {
        // 复用 prompt 里的“8 点采样”概念：N,NE,E,SE,S,SW,W,NW
        double cx = center.getX();
        double cz = center.getZ();
        int rr = (int) Math.round(r / Math.sqrt(2.0));
        int[][] pts = new int[][]{
                {(int) cx, (int) (cz - r)},
                {(int) (cx + rr), (int) (cz - rr)},
                {(int) (cx + r), (int) cz},
                {(int) (cx + rr), (int) (cz + rr)},
                {(int) cx, (int) (cz + r)},
                {(int) (cx - rr), (int) (cz + rr)},
                {(int) (cx - r), (int) cz},
                {(int) (cx - rr), (int) (cz - rr)}
        };
        double px = pos.getX() + 0.5;
        double pz = pos.getZ() + 0.5;
        double best = Double.POSITIVE_INFINITY;
        for (int[] p : pts) {
            double vx = p[0] + 0.5;
            double vz = p[1] + 0.5;
            double dx = px - vx;
            double dz = pz - vz;
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best <= tol * tol;
    }

    private static Direction circleTangent(BlockPos center, int r, BlockPos pos) {
        if (center == null || pos == null) return null;
        double cx = center.getX() + 0.5;
        double cz = center.getZ() + 0.5;
        double px = pos.getX() + 0.5;
        double pz = pos.getZ() + 0.5;
        double dx = px - cx;
        double dz = pz - cz;
        if (Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6) return null;
        // 切线向量 = (-dz, dx)
        int tx = (int) Math.round(-dz * 100.0);
        int tz = (int) Math.round(dx * 100.0);
        Direction d = FacingDeriver.cardinalFromDelta(tx, tz);
        if (d == null) d = Direction.SOUTH;
        return d;
    }

    private static boolean withinYRange(OutlineTool.OutlineShape s, BlockPos pos) {
        if (s == null || pos == null) return true;
        int y = pos.getY();
        // 给一点余量，避免玩家点在边界附近的上下层完全失效
        return y >= (s.minY() - 2) && y <= (s.maxY() + 2);
    }

    /**
     * 射线法：判断点是否在 polygon 内部（XZ）。
     * 采用 half-open 规则以避免落在顶点上时的抖动。
     */
    private static boolean pointInPolygonXZ(double x, double z, List<BlockPos> poly) {
        boolean inside = false;
        int n = poly.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            BlockPos pi = poly.get(i);
            BlockPos pj = poly.get(j);
            if (pi == null || pj == null) continue;
            double xi = pi.getX() + 0.5;
            double zi = pi.getZ() + 0.5;
            double xj = pj.getX() + 0.5;
            double zj = pj.getZ() + 0.5;

            boolean intersect = ((zi > z) != (zj > z))
                    && (x < (xj - xi) * (z - zi) / (zj - zi + 1e-12) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }
}

