package com.formacraft.common.geometry.tool.nobuild;

import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * PolygonNoBuildZone（多边形禁区）
 * 
 * 2D 多边形禁区（在 XZ 平面上）
 * 
 * 应用场景：
 * - 河道
 * - 山体
 * - 保护遗迹
 */
public class PolygonNoBuildZone implements NoBuildZone {

    private final List<Point2> polygon;

    public PolygonNoBuildZone(List<Point2> polygon) {
        this.polygon = polygon != null ? polygon : List.of();
    }

    @Override
    public boolean contains(BlockPos pos) {
        if (pos == null || polygon.isEmpty()) {
            return false;
        }
        return pointInPolygon(pos.getX(), pos.getZ());
    }

    /**
     * 点在多边形内判断（射线法）
     */
    private boolean pointInPolygon(int x, int z) {
        if (polygon.size() < 3) {
            return false;
        }

        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            Point2 pi = polygon.get(i);
            Point2 pj = polygon.get(j);
            
            if (((pi.z() > z) != (pj.z() > z)) &&
                (x < (pj.x() - pi.x()) * (z - pi.z()) / (double)(pj.z() - pi.z()) + pi.x())) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * 2D 点（XZ 平面）
     */
    public record Point2(int x, int z) {}
}

