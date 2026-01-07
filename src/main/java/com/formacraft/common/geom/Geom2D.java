package com.formacraft.common.geom;

import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Geom2D（2D 几何工具）
 * 
 * 核心功能：
 * - Polygon 点内判断（射线法）
 * - AABB 计算
 * - Footprint 采样
 */
public final class Geom2D {
    private Geom2D() {}

    /**
     * 射线法判断点是否在多边形内
     */
    public static boolean pointInPolygon(double x, double z, List<Vec2> poly) {
        if (poly == null || poly.size() < 3) {
            return false;
        }
        
        // Ray casting
        boolean inside = false;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            double xi = poly.get(i).x;
            double zi = poly.get(i).z;
            double xj = poly.get(j).x;
            double zj = poly.get(j).z;

            boolean intersect = ((zi > z) != (zj > z)) &&
                    (x < (xj - xi) * (z - zi) / ((zj - zi) + 1e-12) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    /**
     * 计算多边形的 AABB
     */
    public static AABB2 aabbOfPolygon(List<Vec2> poly) {
        if (poly == null || poly.isEmpty()) {
            return new AABB2(0, 0, 0, 0);
        }
        
        double minX = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        
        for (Vec2 v : poly) {
            minX = Math.min(minX, v.x);
            minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x);
            maxZ = Math.max(maxZ, v.z);
        }
        
        return new AABB2(
                (int) Math.floor(minX),
                (int) Math.floor(minZ),
                (int) Math.ceil(maxX),
                (int) Math.ceil(maxZ)
        );
    }

    /**
     * Footprint 采样：用网格点采样判断是否全部落在 allowed 区域内
     */
    public static boolean sampleFootprintAllAllowed(
            BlockPos anchor,
            int footprintW,
            int footprintD,
            int clearance,
            java.util.function.BiPredicate<Integer, Integer> allowedXZ
    ) {
        int halfW = Math.max(1, footprintW / 2);
        int halfD = Math.max(1, footprintD / 2);
        int rW = halfW + clearance;
        int rD = halfD + clearance;

        // 采样步长：小 footprint 更密，大 footprint 稍稀
        int step = (rW + rD) <= 18 ? 1 : 2;

        int ax = anchor.getX();
        int az = anchor.getZ();

        for (int dx = -rW; dx <= rW; dx += step) {
            for (int dz = -rD; dz <= rD; dz += step) {
                int x = ax + dx;
                int z = az + dz;
                if (!allowedXZ.test(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ----------------
    // small structs
    // ----------------

    /**
     * 2D 向量
     */
    public record Vec2(double x, double z) {}

    /**
     * 2D AABB
     */
    public static final class AABB2 {
        public final int minX, minZ, maxX, maxZ;

        public AABB2(int minX, int minZ, int maxX, int maxZ) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
        }

        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }
}

