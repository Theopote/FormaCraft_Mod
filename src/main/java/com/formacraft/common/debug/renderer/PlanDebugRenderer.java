package com.formacraft.common.debug.renderer;

import com.formacraft.common.debug.DebugContext;
import com.formacraft.common.debug.DebugLayer;
import com.formacraft.common.debug.DebugOverlayRenderer;
import com.formacraft.common.llm.dto.PlanSkeleton;

import java.util.Set;

/**
 * PlanDebugRenderer（PlanSkeleton 层调试渲染器）
 * <p>
 * 渲染 2D 语义层的调试可视化：
 * - Plan Outline
 * - Zone 区域
 * - Axis
 */
public class PlanDebugRenderer implements DebugOverlayRenderer {

    @Override
    public void render(DebugContext ctx, Set<DebugLayer> enabledLayers) {
        if (ctx == null || ctx.planSkeleton == null) {
            return;
        }

        PlanSkeleton plan = ctx.planSkeleton;
        double y = ctx.viewY + 0.05; // 防 z-fight

        // 1. Plan Outline
        if (enabledLayers.contains(DebugLayer.PLAN_OUTLINE)) {
            renderPlanOutline(plan, y, ctx.scale);
        }

        // 2. Zone 区域
        if (enabledLayers.contains(DebugLayer.PLAN_ZONES)) {
            renderZones(plan, y, ctx.scale);
        }

        // 3. Axis
        if (enabledLayers.contains(DebugLayer.PLAN_AXIS_PRIMARY)) {
            renderPrimaryAxes(plan, y, ctx.scale);
        }
        if (enabledLayers.contains(DebugLayer.PLAN_AXIS_SECONDARY)) {
            renderSecondaryAxes(plan, y, ctx.scale);
        }
    }

    @Override
    public boolean supportsLayer(DebugLayer layer) {
        return DebugLayer.planLayers().contains(layer);
    }

    /**
     * 渲染 Plan Outline
     * <p>
     * 画什么：FloorPlate.footprint、Courtyard.footprint
     * 怎么画：Y = baseY + 0.05，线框 polyline，不填充，蓝色
     */
    private void renderPlanOutline(PlanSkeleton plan, double y, double scale) {
        // v1 简化：如果 PlanSkeleton 没有直接存储 outline polygon，
        // 需要从 StructuralSkeleton 获取（这里暂时跳过）
        // 未来：在 PlanSkeleton 中存储 outline polygon
        
        // TODO: 实际渲染逻辑（客户端实现）
        // drawPolylineXZ(footprint, y, DebugColors.PLAN_OUTLINE);
    }

    /**
     * 渲染 Zone 区域
     * <p>
     * 画什么：每个 zone 的 polygon（如果有）
     * 怎么画：半透明填充，不同 zone 可随机浅色，绿色
     */
    private void renderZones(PlanSkeleton plan, double y, double scale) {
        if (plan.zones() == null) {
        }

        // v1 简化：PlanSkeleton 的 zones 没有直接存储 polygon
        // 需要从 StructuralSkeleton 或其他来源获取
        // 未来：在 PlanSkeleton 中存储 zone polygons
        
        // TODO: 实际渲染逻辑（客户端实现）
        // for (PlanSkeleton.Zone zone : plan.zones()) {
        //     Polygon2D zonePolygon = getZonePolygon(zone);
        //     fillPolygonXZ(zonePolygon, y, DebugColors.PLAN_ZONES);
        // }
    }

    /**
     * 渲染主轴 Axis
     * <p>
     * 画什么：AxisConstraint.axis（PRIMARY）
     * 怎么画：无限延伸或限定长度，红色，可加文字标签
     */
    private void renderPrimaryAxes(PlanSkeleton plan, double y, double scale) {
        if (plan.axes() == null) {
        }

        // TODO: 实际渲染逻辑（客户端实现）
        // for (PlanSkeleton.Axis axis : plan.axes()) {
        //     if ("primary".equalsIgnoreCase(axis.role())) {
        //         Line2D axisLine = getAxisLine(axis);
        //         drawLineXZ(axisLine, y, DebugColors.PLAN_AXIS_PRIMARY);
        //         drawText("PRIMARY AXIS", axisLine.midPoint(), y);
        //     }
        // }
    }

    /**
     * 渲染次轴 Axis
     * <p>
     * 画什么：AxisConstraint.axis（SECONDARY）
     * 怎么画：无限延伸或限定长度，橙色
     */
    private void renderSecondaryAxes(PlanSkeleton plan, double y, double scale) {
        if (plan.axes() == null) {
        }

        // TODO: 实际渲染逻辑（客户端实现）
        // for (PlanSkeleton.Axis axis : plan.axes()) {
        //     if (!"primary".equalsIgnoreCase(axis.role())) {
        //         Line2D axisLine = getAxisLine(axis);
        //         drawLineXZ(axisLine, y, DebugColors.PLAN_AXIS_SECONDARY);
        //     }
        // }
    }
}
