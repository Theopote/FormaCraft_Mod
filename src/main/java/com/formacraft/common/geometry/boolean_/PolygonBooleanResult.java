package com.formacraft.common.geometry.boolean_;

import com.formacraft.common.geometry.Polygon2D;
import com.formacraft.common.geometry.Polyline2D;

import java.util.List;

/**
 * PolygonBooleanResult（多边形 Boolean 运算结果）
 * <p>
 * 存储 Boolean 运算的结果：
 * - outerBoundaries: 外边界（一个或多个 polygon）
 * - holeBoundaries: 洞的边界（每个洞的边界 polyline）
 */
public class PolygonBooleanResult {
    /** 外边界（subtract 后的有效区域） */
    private final List<Polygon2D> outerBoundaries;

    /** 洞的边界（每个洞的边界 polyline） */
    private final List<Polyline2D> holeBoundaries;

    public PolygonBooleanResult(
            List<Polygon2D> outerBoundaries,
            List<Polyline2D> holeBoundaries
    ) {
        this.outerBoundaries = outerBoundaries != null ? List.copyOf(outerBoundaries) : List.of();
        this.holeBoundaries = holeBoundaries != null ? List.copyOf(holeBoundaries) : List.of();
    }

    public List<Polygon2D> getOuterBoundaries() {
        return outerBoundaries;
    }

    public List<Polyline2D> getHoleBoundaries() {
        return holeBoundaries;
    }

    public boolean isEmpty() {
        return outerBoundaries.isEmpty();
    }

    public int outerBoundaryCount() {
        return outerBoundaries.size();
    }

    public int holeCount() {
        return holeBoundaries.size();
    }
}
