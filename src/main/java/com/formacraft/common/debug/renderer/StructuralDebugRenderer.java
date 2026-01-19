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

        // 4. Roof
        if (enabledLayers.contains(DebugLayer.STRUCT_ROOF)) {
            renderRoof(structural, y, ctx.scale);
        }

        // 5. Roof Ridge（v2）
        if (enabledLayers.contains(DebugLayer.STRUCT_ROOF_RIDGE)) {
            renderRoofRidges(structural, y, ctx.scale);
        }

        // 6. Roof Slope（v2）
        if (enabledLayers.contains(DebugLayer.STRUCT_ROOF_SLOPE)) {
            renderRoofSlopes(structural, y, ctx.scale);
        }

        // 7. Roof Ridge v3（按类型细分）
        if (enabledLayers.contains(DebugLayer.ROOF_RIDGE_MAIN)) {
            renderRidgesByType(structural, y, ctx.scale, com.formacraft.common.llm.dto.structural.RidgeType.MAIN_RIDGE, com.formacraft.common.debug.color.DebugColors.ROOF_RIDGE_MAIN);
        }
        if (enabledLayers.contains(DebugLayer.ROOF_RIDGE_HIP)) {
            renderRidgesByType(structural, y, ctx.scale, com.formacraft.common.llm.dto.structural.RidgeType.HIP_RIDGE, com.formacraft.common.debug.color.DebugColors.ROOF_RIDGE_HIP);
        }
        if (enabledLayers.contains(DebugLayer.ROOF_RIDGE_DIAGONAL)) {
            renderRidgesByType(structural, y, ctx.scale, com.formacraft.common.llm.dto.structural.RidgeType.DIAGONAL_RIDGE, com.formacraft.common.debug.color.DebugColors.ROOF_RIDGE_DIAGONAL);
        }

        // 8. Roof Slope Triangle（v3）
        if (enabledLayers.contains(DebugLayer.ROOF_SLOPE_TRI)) {
            renderTriangularSlopes(structural, y, ctx.scale);
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

        for (StructuralSkeleton.CourtyardVoid courtyard : structural.courtyards) {
            if (courtyard.footprint != null) {
                // TODO: 实际渲染逻辑（客户端实现）
                // fillPolygonXZ(courtyard.footprint, y, DebugColors.STRUCT_COURTYARD_VOID);
                // drawText("VOID", courtyard.footprint.centroid(), y);
            }
        }
    }

    /**
     * 渲染 Roof（屋顶体量）
     * <p>
     * 画什么：RoofPlate.roofFootprints
     * 怎么画：半透明灰色，或线框
     * <p>
     * 你会立刻看到：
     * - 屋顶有没有盖住庭院 ❌ / ✅
     * - 多体量是否各自封顶
     * - 是否和墙顶对齐
     */
    private void renderRoof(StructuralSkeleton structural, double y, double scale) {
        if (structural.roofPlate == null || structural.roofPlate.roofFootprints.isEmpty()) {
        }

        // TODO: 实际渲染逻辑（客户端实现）
        // for (Polygon2D roofFootprint : structural.roofPlate.roofFootprints) {
        //     // 渲染屋顶 polygon（在 roofPlate.baseY 高度）
        //     fillPolygonXZ(roofFootprint, structural.roofPlate.baseY, DebugColors.STRUCT_ROOF);
        //     // 或渲染线框
        //     drawPolylineXZ(roofFootprint.getBoundary(), structural.roofPlate.baseY, DebugColors.STRUCT_ROOF);
        // }
    }

    /**
     * 渲染 Roof Ridge（脊线）
     * <p>
     * 画什么：RidgeLine.lineXZ
     * 怎么画：粗红线（XZ）+ 高度标注
     * <p>
     * 你会非常直观地看到：
     * - 脊线有没有跑偏
     * - 脊线高度是否合理
     */
    private void renderRoofRidges(StructuralSkeleton structural, double y, double scale) {
        if (structural.roofPlate == null || structural.roofPlate.ridges.isEmpty()) {
        }

        // TODO: 实际渲染逻辑（客户端实现）
        // for (RidgeLine ridge : structural.roofPlate.ridges) {
        //     // 在 ridge.heightY 高度绘制脊线
        //     drawLineXZ(ridge.lineXZ, ridge.heightY, DebugColors.STRUCT_ROOF_RIDGE);
        //     // 可加高度标注
        //     Vec2 midPoint = ridge.lineXZ.midPoint();
        //     drawText(String.format("Ridge: %.1f", ridge.heightY), midPoint, ridge.heightY);
        // }
    }

    /**
     * 渲染 Roof Slope（坡面）
     * <p>
     * 画什么：RoofSlope.area
     * 怎么画：半透明斜面
     * <p>
     * 你会非常直观地看到：
     * - 坡面是否对称
     * - Courtyard 是否完全敞开
     */
    private void renderRoofSlopes(StructuralSkeleton structural, double y, double scale) {
        if (structural.roofPlate == null || structural.roofPlate.slopes.isEmpty()) {
        }

        // TODO: 实际渲染逻辑（客户端实现）
        // for (RoofSlope slope : structural.roofPlate.slopes) {
        //     // 渲染坡面（根据 normal 和 pitch 计算 3D 几何）
        //     fillPolygonXZ3D(slope.area, slope.normal, slope.pitch, DebugColors.STRUCT_ROOF_SLOPE);
        // }
    }

    /**
     * 按类型渲染脊线（v3）
     * <p>
     * 画什么：RidgeLine（按类型）
     * 怎么画：
     * - MAIN_RIDGE：粗红
     * - HIP_RIDGE：橙
     * - DIAGONAL_RIDGE：紫
     * <p>
     * 你会第一次真正"看到"歇山结构
     */
    private void renderRidgesByType(
            StructuralSkeleton structural,
            double y,
            double scale,
            com.formacraft.common.llm.dto.structural.RidgeType targetType,
            java.awt.Color color
    ) {
        if (structural.roofPlate == null) {
        }

        // TODO: 实际渲染逻辑（客户端实现）
        // for (RidgeLine ridge : structural.roofPlate.ridges) {
        //     if (ridge.type == targetType) {
        //         // 使用 3D 脊线或 2D 投影
        //         Line3D line3D = ridge.line3D;
        //         Line2D line2D = ridge.lineXZ;
        //         if (line3D != null) {
        //             // 渲染 3D 脊线
        //             drawLine3D(line3D, color);
        //         } else if (line2D != null) {
        //             // 渲染 2D 脊线（在 heightY 高度）
        //             drawLineXZ(line2D, ridge.heightY, color);
        //         }
        //     }
        // }
    }

    /**
     * 渲染三角坡面（v3）
     * <p>
     * 画什么：歇山四个角的三角坡面
     * 怎么画：半透明分色
     */
    private void renderTriangularSlopes(StructuralSkeleton structural, double y, double scale) {
        if (structural.roofPlate == null) {
        }

        // TODO: 实际渲染逻辑（客户端实现）
        // for (RoofSlope slope : structural.roofPlate.slopes) {
        //     // 检查是否是三角坡面（vertexCount == 3）
        //     if (slope.area != null && slope.area.vertexCount() == 3) {
        //         // 渲染三角坡面
        //         fillPolygonXZ3D(slope.area3D, slope.normal, slope.pitch, DebugColors.ROOF_SLOPE_TRI);
        //     }
        // }
    }
}
