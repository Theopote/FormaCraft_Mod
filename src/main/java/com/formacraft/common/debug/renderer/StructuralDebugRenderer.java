package com.formacraft.common.debug.renderer;

import com.formacraft.common.debug.DebugContext;
import com.formacraft.common.debug.DebugLayer;
import com.formacraft.common.debug.DebugOverlayRenderer;
import com.formacraft.common.debug.color.DebugColors;
import com.formacraft.common.llm.dto.StructuralSkeleton;

import java.util.Set;

/**
 * StructuralDebugRenderer（StructuralSkeleton 层调试渲染器）
 * <p>
 * 渲染 3D 结构层的调试可视化：
 * - WallSegment Baseline（外墙 / 庭院墙 / 内墙）
 * - WallSegment Solid（Extrusion 结果）
 * - Courtyard Void（负空间）
 * <p>
 * 这是最关键的一层，90% 的几何 bug 在这一层暴露
 */
public class StructuralDebugRenderer implements DebugOverlayRenderer {

    @Override
    public void render(DebugContext ctx, Set<DebugLayer> enabledLayers) {
        if (ctx == null || ctx.structuralSkeleton == null) {
            return;
        }

        StructuralSkeleton structural = ctx.structuralSkeleton;
        double y = ctx.viewY + 0.05; // 防 z-fight

        // 1. Wall Baseline（按类型分组）
        if (enabledLayers.contains(DebugLayer.STRUCT_WALL_BASELINE_EXTERNAL)) {
            renderWallBaselines(structural, y, ctx.scale, com.formacraft.common.llm.dto.structural.WallType.EXTERNAL, DebugColors.STRUCT_WALL_BASELINE_EXTERNAL);
        }
        if (enabledLayers.contains(DebugLayer.STRUCT_WALL_BASELINE_COURTYARD)) {
            renderWallBaselines(structural, y, ctx.scale, com.formacraft.common.llm.dto.structural.WallType.COURTYARD, DebugColors.STRUCT_WALL_BASELINE_COURTYARD);
        }
        if (enabledLayers.contains(DebugLayer.STRUCT_WALL_BASELINE_INTERNAL)) {
            renderWallBaselines(structural, y, ctx.scale, com.formacraft.common.llm.dto.structural.WallType.INTERNAL, DebugColors.STRUCT_WALL_BASELINE_INTERNAL);
        }

        // 2. Wall Solid
        if (enabledLayers.contains(DebugLayer.STRUCT_WALL_SOLID)) {
            renderWallSolids(structural, y, ctx.scale);
        }

        // 3. Courtyard Void
        if (enabledLayers.contains(DebugLayer.STRUCT_COURTYARD_VOID)) {
            renderCourtyardVoids(structural, y, ctx.scale);
        }
    }

    @Override
    public boolean supportsLayer(DebugLayer layer) {
        return DebugLayer.structuralLayers().contains(layer);
    }

    /**
     * 渲染 WallSegment Baseline
     * <p>
     * 画什么：WallSegment.baseline
     * 怎么画：不拉高，XZ 折线，颜色由 type 决定
     * <p>
     * 如果这里就不对，后面一律不用看
     */
    private void renderWallBaselines(
            StructuralSkeleton structural,
            double y,
            double scale,
            com.formacraft.common.llm.dto.structural.WallType targetType,
            java.awt.Color color
    ) {
        if (structural.walls == null) {
            return;
        }

        for (StructuralSkeleton.WallSegment wall : structural.walls) {
            if (wall.type == targetType && wall.baseline != null) {
                // TODO: 实际渲染逻辑（客户端实现）
                // drawPolylineXZ(wall.baseline, y, color);
            }
        }
    }

    /**
     * 渲染 WallSegment Solid（Extrusion 结果）
     * <p>
     * 画什么：ExtrudedSolid（墙体）
     * 怎么画：半透明盒子，或仅画线框（推荐）
     * <p>
     * 一眼就能看到：
     * - 墙厚是否对
     * - 高度是否统一
     * - 转角是否爆炸
     */
    private void renderWallSolids(StructuralSkeleton structural, double y, double scale) {
        if (structural.walls == null) {
            return;
        }

        for (StructuralSkeleton.WallSegment wall : structural.walls) {
            // 从 WallSegment 获取 ExtrudedSolid（存储在 ExecutableSkeletonPlan 中）
            // v1 简化：需要从 ctx.executablePlans 中查找对应的 ExtrudedSolid
            // 未来：可以在 StructuralSkeleton 中直接存储 ExtrudedSolid
            
            // TODO: 实际渲染逻辑（客户端实现）
            // ExtrudedSolid solid = getExtrudedSolid(wall);
            // if (solid != null) {
            //     drawExtrudedSolidWireframe(solid, DebugColors.STRUCT_WALL_SOLID);
            // }
        }
    }

    /**
     * 渲染 Courtyard Void（负空间）
     * <p>
     * 画什么：CourtyardVoid.footprint、Void 体量
     * 怎么画：地面画深色网格，可加 downward arrow
     * <p>
     * 这是判断 Boolean 减法是否成功的关键
     */
    private void renderCourtyardVoids(StructuralSkeleton structural, double y, double scale) {
        if (structural.courtyards == null) {
            return;
        }

        for (StructuralSkeleton.CourtyardVoid courtyard : structural.courtyards) {
            if (courtyard.footprint != null) {
                // TODO: 实际渲染逻辑（客户端实现）
                // fillPolygonXZ(courtyard.footprint, y, DebugColors.STRUCT_COURTYARD_VOID);
                // drawText("VOID", courtyard.footprint.centroid(), y);
            }
        }
    }
}
