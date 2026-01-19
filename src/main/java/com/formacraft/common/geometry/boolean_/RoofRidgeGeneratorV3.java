package com.formacraft.common.geometry.boolean_;

import com.formacraft.common.geometry.*;
import com.formacraft.common.llm.dto.StructuralSkeleton;
import com.formacraft.common.llm.dto.structural.*;

import java.util.ArrayList;
import java.util.List;

/**
 * RoofRidgeGeneratorV3（屋顶脊线生成器 v3）
 * <p>
 * 核心职责：根据 RoofForm 生成中式屋顶的脊系统
 * <p>
 * 支持的屋顶形式：
 * - HIP（庑殿顶）
 * - XIESHAN（歇山顶）
 * <p>
 * 核心思想：中式屋顶不是"一个屋顶"，而是一个脊的系统
 */
public final class RoofRidgeGeneratorV3 {

    private RoofRidgeGeneratorV3() {}

    /**
     * 生成脊线系统
     * <p>
     * 根据 RoofForm 生成相应的脊线系统：
     * - HIP → 4 条 HIP_RIDGE（可能 + 短 MAIN_RIDGE）
     * - XIESHAN → MAIN_RIDGE + 2 条 HIP_RIDGE + 4 条 DIAGONAL_RIDGE
     *
     * @param roofPlate RoofPlate
     * @param structural StructuralSkeleton（用于获取 Axis 信息）
     * @return 生成的 RidgeLine 列表
     */
    public static List<RidgeLine> generateRidgeSystem(
            StructuralSkeleton.RoofPlate roofPlate,
            StructuralSkeleton structural
    ) {
        if (roofPlate == null || roofPlate.form == null) {
            return List.of();
        }

        return switch (roofPlate.form) {
            case HIP -> generateHipRidges(roofPlate, structural);
            case XIESHAN -> generateXieshanRidges(roofPlate, structural);
            default -> List.of(); // v2 形式（GABLED / AXIAL_GABLED）在 v2 中处理
        };
    }

    /**
     * 生成庑殿顶（HIP）脊线系统
     * <p>
     * 结构：
     * - 无长正脊（或中心短 MAIN_RIDGE）
     * - 从中心向四角生成 4 条 HIP_RIDGE
     * <p>
     * 几何：
     * ```
     *       △
     *      / \
     *     △───△
     *      \ /
     *       △
     * ```
     */
    private static List<RidgeLine> generateHipRidges(
            StructuralSkeleton.RoofPlate roofPlate,
            StructuralSkeleton structural
    ) {
        List<RidgeLine> ridges = new ArrayList<>();

        if (roofPlate.roofFootprints.isEmpty()) {
            return ridges;
        }

        // v3 简化：使用第一个 footprint
        Polygon2D footprint = roofPlate.roofFootprints.getFirst();
        Vec2 centroid = footprint.centroid();

        // 计算四角
        Polygon2D.Bounds2D bounds = footprint.getBounds();
        Vec2 min = bounds.min();
        Vec2 max = bounds.max();

        // 估算屋顶中心高度（使用 baseY + wallHeight + ridgeLift）
        double centerHeight = roofPlate.baseY + 5.0 + 2.0; // v3 简化：使用默认值

        // 中心点（3D）
        Vec3 center = new Vec3(centroid.x(), centerHeight, centroid.z());

        // 四角点（3D，高度略低）
        Vec3 corner1 = new Vec3(min.x(), centerHeight - 1.0, min.z());
        Vec3 corner2 = new Vec3(max.x(), centerHeight - 1.0, min.z());
        Vec3 corner3 = new Vec3(max.x(), centerHeight - 1.0, max.z());
        Vec3 corner4 = new Vec3(min.x(), centerHeight - 1.0, max.z());

        // 生成 4 条 HIP_RIDGE（从中心向四角）
        ridges.add(new RidgeLine(new Line3D(center, corner1), RidgeType.HIP_RIDGE, RidgeRole.MAIN));
        ridges.add(new RidgeLine(new Line3D(center, corner2), RidgeType.HIP_RIDGE, RidgeRole.MAIN));
        ridges.add(new RidgeLine(new Line3D(center, corner3), RidgeType.HIP_RIDGE, RidgeRole.MAIN));
        ridges.add(new RidgeLine(new Line3D(center, corner4), RidgeType.HIP_RIDGE, RidgeRole.MAIN));

        return ridges;
    }

    /**
     * 生成歇山顶（XIESHAN）脊线系统
     * <p>
     * 结构：
     * - 正脊（MAIN_RIDGE）：沿 Primary Axis
     * - 垂脊（HIP_RIDGE）：从正脊两端向下
     * - 戗脊（DIAGONAL_RIDGE）：从垂脊中段斜向连接至檐角
     * <p>
     * 几何：
     * ```
     *        ───────        ← 正脊
     *       /   |   \
     *      /    |    \
     *     /     |     \
     *    └──────┼──────┘
     *           ↓
     *        垂脊 + 戗脊
     * ```
     */
    private static List<RidgeLine> generateXieshanRidges(
            StructuralSkeleton.RoofPlate roofPlate,
            StructuralSkeleton structural
    ) {
        List<RidgeLine> ridges = new ArrayList<>();

        if (roofPlate.roofFootprints.isEmpty()) {
            return ridges;
        }

        Polygon2D footprint = roofPlate.roofFootprints.getFirst();
        Polygon2D.Bounds2D bounds = footprint.getBounds();

        // Step 1: 生成正脊（MAIN_RIDGE）
        // 沿 Primary Axis（如果有），否则沿长轴
        Line2D mainRidge2D = findMainRidgeAxis(footprint, structural);
        double ridgeHeight = roofPlate.baseY + 5.0 + 2.0;

        Vec3 mainRidgeStart = new Vec3(mainRidge2D.start().x(), ridgeHeight, mainRidge2D.start().z());
        Vec3 mainRidgeEnd = new Vec3(mainRidge2D.end().x(), ridgeHeight, mainRidge2D.end().z());

        RidgeLine mainRidge = new RidgeLine(new Line3D(mainRidgeStart, mainRidgeEnd), RidgeType.MAIN_RIDGE, RidgeRole.MAIN);
        ridges.add(mainRidge);

        // Step 2: 生成垂脊（HIP_RIDGE）
        // 从正脊两端向下
        Vec2 boundsCenter = footprint.centroid();
        double hipRidgeHeight = ridgeHeight - 1.5;

        // 垂脊起点（正脊两端）

        // 垂脊终点（沿最陡方向向下，v3 简化：使用默认方向）
        Vec3 hipEnd1 = new Vec3(mainRidgeStart.x(), hipRidgeHeight, boundsCenter.z());
        Vec3 hipEnd2 = new Vec3(mainRidgeEnd.x(), hipRidgeHeight, boundsCenter.z());

        RidgeLine hipRidge1 = new RidgeLine(new Line3D(mainRidgeStart, hipEnd1), RidgeType.HIP_RIDGE, RidgeRole.MAIN);
        RidgeLine hipRidge2 = new RidgeLine(new Line3D(mainRidgeEnd, hipEnd2), RidgeType.HIP_RIDGE, RidgeRole.MAIN);
        ridges.add(hipRidge1);
        ridges.add(hipRidge2);

        // Step 3: 生成戗脊（DIAGONAL_RIDGE）
        // 从垂脊中段斜向连接至檐角
        Vec2 min = bounds.min();
        Vec2 max = bounds.max();

        // 四角点（檐角）
        Vec3 eaveCorner1 = new Vec3(min.x(), hipRidgeHeight - 0.5, min.z());
        Vec3 eaveCorner2 = new Vec3(max.x(), hipRidgeHeight - 0.5, min.z());
        Vec3 eaveCorner3 = new Vec3(max.x(), hipRidgeHeight - 0.5, max.z());
        Vec3 eaveCorner4 = new Vec3(min.x(), hipRidgeHeight - 0.5, max.z());

        // 垂脊中段点
        Vec3 hipMid1 = hipRidge1.line3D.midpoint();
        Vec3 hipMid2 = hipRidge2.line3D.midpoint();

        // 生成 4 条戗脊（v3 简化：从垂脊中段到最近的檐角）
        ridges.add(new RidgeLine(new Line3D(hipMid1, eaveCorner1), RidgeType.DIAGONAL_RIDGE, RidgeRole.SECONDARY));
        ridges.add(new RidgeLine(new Line3D(hipMid1, eaveCorner4), RidgeType.DIAGONAL_RIDGE, RidgeRole.SECONDARY));
        ridges.add(new RidgeLine(new Line3D(hipMid2, eaveCorner2), RidgeType.DIAGONAL_RIDGE, RidgeRole.SECONDARY));
        ridges.add(new RidgeLine(new Line3D(hipMid2, eaveCorner3), RidgeType.DIAGONAL_RIDGE, RidgeRole.SECONDARY));

        return ridges;
    }

    /**
     * 查找主脊轴线
     * <p>
     * 优先级：
     * 1. Primary Axis（如果有）
     * 2. 长轴（footprint 边界框的长边）
     */
    private static Line2D findMainRidgeAxis(Polygon2D footprint, StructuralSkeleton structural) {
        // 优先使用 Primary Axis
        if (structural != null && structural.axes != null) {
            for (StructuralSkeleton.AxisConstraint axis : structural.axes) {
                if (axis.role == AxisRole.PRIMARY && axis.axis != null) {
                    return axis.axis;
                }
            }
        }

        // Fallback：使用 footprint 的长轴
        Polygon2D.Bounds2D bounds = footprint.getBounds();
        Vec2 min = bounds.min();
        Vec2 max = bounds.max();

        double width = max.x() - min.x();
        double depth = max.z() - min.z();

        if (width >= depth) {
            // 沿 X 轴
            return new Line2D(
                    new Vec2(min.x(), (min.z() + max.z()) / 2.0),
                    new Vec2(max.x(), (min.z() + max.z()) / 2.0)
            );
        } else {
            // 沿 Z 轴
            return new Line2D(
                    new Vec2((min.x() + max.x()) / 2.0, min.z()),
                    new Vec2((min.x() + max.x()) / 2.0, max.z())
            );
        }
    }
}
