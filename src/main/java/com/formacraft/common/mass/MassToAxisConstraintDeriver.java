package com.formacraft.common.mass;

import com.formacraft.common.geometry.Line2D;
import com.formacraft.common.geometry.Vec2;
import com.formacraft.common.llm.dto.PlanSkeleton;
import com.formacraft.common.llm.dto.StructuralSkeleton;
import com.formacraft.common.llm.dto.structural.AxisRole;
import com.formacraft.FormacraftMod;

import java.util.ArrayList;
import java.util.List;

/**
 * MassToAxisConstraintDeriver（体量到轴线约束派生器）
 * <p>
 * 核心职责：从 MassAssembly.domain（PlanSkeleton）提取 axes，并转换为 AxisConstraint
 * <p>
 * 转换规则：
 * - PlanSkeleton.Axis → StructuralSkeleton.AxisConstraint
 * - role 映射：primary → PRIMARY, 其他 → SECONDARY
 * - zones 保持不变
 * - 生成默认轴线几何（v1 简化）
 * <p>
 * 这是 Phase 2 的实现：从 domain 提取 axes
 */
public final class MassToAxisConstraintDeriver {

    private MassToAxisConstraintDeriver() {}

    /**
     * 从 MassAssembly.domain 提取 axes
     * <p>
     * 从 PlanSkeleton.axes 转换为 StructuralSkeleton.AxisConstraint 列表
     *
     * @param massAssembly 体量组合（包含 domain）
     * @return AxisConstraint 列表
     */
    public static List<StructuralSkeleton.AxisConstraint> deriveAxisConstraints(MassAssembly massAssembly) {
        if (massAssembly == null || massAssembly.domain == null) {
            return List.of();
        }

        PlanSkeleton domain = massAssembly.domain;
        if (domain.axes() == null || domain.axes().isEmpty()) {
            return List.of();
        }

        List<StructuralSkeleton.AxisConstraint> constraints = new ArrayList<>();

        for (PlanSkeleton.Axis axis : domain.axes()) {
            if (axis == null || axis.id() == null) {
                continue;
            }

            try {
                StructuralSkeleton.AxisConstraint constraint = convertAxisToConstraint(axis, domain);
                if (constraint != null) {
                    constraints.add(constraint);
                }
            } catch (Exception e) {
                FormacraftMod.LOGGER.warn("MassToAxisConstraintDeriver: Failed to convert axis {}: {}", axis.id(), e.getMessage());
            }
        }

        return constraints;
    }

    /**
     * 将 PlanSkeleton.Axis 转换为 StructuralSkeleton.AxisConstraint
     *
     * @param axis PlanSkeleton.Axis
     * @param domain PlanSkeleton（用于获取上下文信息）
     * @return AxisConstraint
     */
    private static StructuralSkeleton.AxisConstraint convertAxisToConstraint(
            PlanSkeleton.Axis axis,
            PlanSkeleton domain
    ) {
        // 1. 决定 role
        AxisRole role;
        String axisRole = axis.role();
        if (axisRole != null && "primary".equalsIgnoreCase(axisRole)) {
            role = AxisRole.PRIMARY;
        } else {
            role = AxisRole.SECONDARY;
        }

        // 2. 生成轴线几何（v1 简化：生成默认轴线）
        // v1 简化：生成东西方向的默认轴线（通过原点）
        // 未来：可以从 axis.zones 的实际位置推断轴线方向
        Line2D axisLine = generateDefaultAxisLine(axis, domain);

        // 3. 提取 zones
        List<String> zones = axis.zones() != null ? new ArrayList<>(axis.zones()) : new ArrayList<>();

        return new StructuralSkeleton.AxisConstraint(
                axis.id(),
                axisLine,
                role,
                zones
        );
    }

    /**
     * 生成默认轴线几何（v1 简化）
     * <p>
     * v1 简化：生成东西方向的默认轴线（通过原点，长度 20）
     * 未来：可以从 axis.zones 的实际位置推断轴线方向
     *
     * @param axis PlanSkeleton.Axis
     * @param domain PlanSkeleton（用于获取上下文信息）
     * @return Line2D（轴线）
     */
    private static Line2D generateDefaultAxisLine(PlanSkeleton.Axis axis, PlanSkeleton domain) {
        // v1 简化：生成东西方向的默认轴线
        // 默认长度：20 block
        double axisLength = 20.0;
        double halfLength = axisLength / 2.0;

        Vec2 start = new Vec2(-halfLength, 0.0);
        Vec2 end = new Vec2(halfLength, 0.0);

        return new Line2D(start, end);
    }

    /**
     * 从 PlanSkeleton 直接提取 axes（便捷方法）
     * <p>
     * 如果已有 PlanSkeleton，可以直接调用此方法
     *
     * @param domain PlanSkeleton（domain）
     * @return AxisConstraint 列表
     */
    public static List<StructuralSkeleton.AxisConstraint> deriveAxisConstraintsFromDomain(PlanSkeleton domain) {
        if (domain == null) {
            return List.of();
        }

        // 创建临时的 MassAssembly
        MassAssembly tempAssembly = new MassAssembly(domain, List.of(), List.of());
        return deriveAxisConstraints(tempAssembly);
    }
}
