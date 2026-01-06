package com.formacraft.common.geometry.tool.footprint;

import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * PolygonFootprint（多边形轮廓）
 * 
 * 2D 多边形轮廓（在 XZ 平面上）
 * 
 * 应用场景：
 * - "只在选区内建造"
 * - "在轮廓范围内生成寺庙"
 * - Patch 自动裁剪
 */
public class PolygonFootprint implements FootprintRegion {

    private final List<Point2> polygon;

    public PolygonFootprint(List<Point2> polygon) {
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

