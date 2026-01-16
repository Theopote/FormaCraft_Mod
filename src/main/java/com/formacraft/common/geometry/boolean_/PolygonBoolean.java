package com.formacraft.common.geometry.boolean_;

import com.formacraft.common.geometry.Polygon2D;
import com.formacraft.common.geometry.Polyline2D;
import com.formacraft.common.geometry.Vec2;

import java.util.ArrayList;
import java.util.List;

/**
 * PolygonBoolean（多边形 Boolean 运算）
 * <p>
 * v1 实现：简单的多边形差集运算
 * <p>
 * 核心思想：
 * - 在 2D 平面（XZ）做 Boolean
 * - 在 3D 只做 Extrusion
 * <p>
 * v1 简化实现：
 * - 使用简单的点内判断 + 边界提取
 * - 不实现完整的 Weiler–Atherton 算法
 * - 适合 Minecraft 规模（多边形点数小）
 */
public final class PolygonBoolean {

    private PolygonBoolean() {}

    /**
     * 从 base polygon 中减去 holes
     * <p>
     * 结果：
     * - outerBoundaries: 减去洞后的有效区域
     * - holeBoundaries: 每个洞的边界（用于生成庭院墙）
     * 
     * @param base 基础 polygon
     * @param holes 要减去的洞（polygon 列表）
     * @return Boolean 运算结果
     */
    public static PolygonBooleanResult subtract(Polygon2D base, List<Polygon2D> holes) {
        if (base == null || base.getVertices().isEmpty()) {
            return new PolygonBooleanResult(List.of(), List.of());
        }

        if (holes == null || holes.isEmpty()) {
            // 没有洞，直接返回 base
            List<Polyline2D> emptyHoles = List.of();
            return new PolygonBooleanResult(List.of(base), emptyHoles);
        }

        // v1 简化实现：
        // 1. 提取洞的边界（用于生成庭院墙）
        List<Polyline2D> holeBoundaries = new ArrayList<>();
        for (Polygon2D hole : holes) {
            if (hole != null && !hole.getVertices().isEmpty()) {
                // 将 polygon 转换为 polyline（闭合）
                List<Vec2> vertices = hole.getVertices();
                holeBoundaries.add(new Polyline2D(vertices));
            }
        }

        // 2. 计算有效区域（v1 简化：使用采样点过滤）
        // 未来：实现完整的多边形裁剪算法
        Polygon2D effectiveFootprint = computeEffectiveFootprint(base, holes);

        List<Polygon2D> outerBoundaries = effectiveFootprint != null
                ? List.of(effectiveFootprint)
                : List.of();

        return new PolygonBooleanResult(outerBoundaries, holeBoundaries);
    }

    /**
     * 计算有效 footprint（v1 简化实现）
     * <p>
     * 策略：
     * - 如果 base 完全包含所有 holes，返回 base（洞会在后续墙体生成时处理）
     * - 如果 holes 与 base 重叠，简化处理：返回 base（v1 保守策略）
     * <p>
     * 未来：实现完整的多边形裁剪算法（Weiler–Atherton / Greiner–Hormann）
     */
    private static Polygon2D computeEffectiveFootprint(Polygon2D base, List<Polygon2D> holes) {
        // v1 简化：直接返回 base
        // 洞的处理在墙体生成时完成（从 holeBoundaries 生成庭院墙）
        // 这样避免了复杂的多边形裁剪，同时保证了系统的稳定性
        
        // 未来可以在这里实现：
        // - 检查 base 是否包含 holes
        // - 执行多边形差集运算
        // - 处理多个结果 polygon
        
        return base;
    }

    /**
     * 从 polygon 提取边界 polyline
     * <p>
     * 用于生成墙基线
     */
    public static Polyline2D extractBoundary(Polygon2D polygon) {
        if (polygon == null || polygon.getVertices().isEmpty()) {
            return null;
        }

        List<Vec2> vertices = polygon.getVertices();
        return new Polyline2D(vertices);
    }
}
