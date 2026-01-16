package com.formacraft.common.llm.converter;

import com.formacraft.common.geometry.*;
import com.formacraft.common.llm.dto.PlanSkeleton;
import com.formacraft.common.llm.dto.StructuralSkeleton;
import com.formacraft.common.llm.dto.structural.*;

import java.util.*;

/**
 * PlanSkeleton → StructuralSkeleton 转换器
 * <p>
 * 核心职责：将 PlanSkeleton 的"几何语义"转换为 StructuralSkeleton 的"3D结构骨架"
 * <p>
 * 映射规则：
 * 1. outline → FLOOR_PLATE（唯一必定存在的元素）
 * 2. edges → WALL_SEGMENT
 *    - external_wall → EXTERNAL
 *    - shared_wall → INTERNAL
 *    - courtyard_wall → COURTYARD
 * 3. courtyards → COURTYARD_VOID
 * 4. axes → AlignmentConstraint
 * <p>
 * 设计原则：
 * - 不关心风格、不关心构件，只关心结构
 * - 保守转换：优先生成合理的默认值
 * - 可扩展：为未来更复杂的几何推断预留接口
 */
public final class PlanSkeletonToStructuralSkeletonConverter {

    private PlanSkeletonToStructuralSkeletonConverter() {}

    // 默认值（v1 简化）
    private static final double DEFAULT_WALL_HEIGHT = 5.0;
    private static final double DEFAULT_FLOOR_Y = 0.0;
    private static final double DEFAULT_WALL_THICKNESS = 1.0;
    private static final double DEFAULT_FLOOR_THICKNESS = 1.0;

    /**
     * 转换 PlanSkeleton 为 StructuralSkeleton
     * 
     * @param planSkeleton PlanSkeleton（2D 几何语义）
     * @return StructuralSkeleton（3D 结构骨架）
     */
    public static StructuralSkeleton convert(PlanSkeleton planSkeleton) {
        if (planSkeleton == null) {
            throw new IllegalArgumentException("PlanSkeleton cannot be null");
        }

        // 1. 生成 floor plate（从 outline 推断，v1 简化：使用默认矩形）
        StructuralSkeleton.FloorPlate floorPlate = generateFloorPlate(planSkeleton);

        // 2. 生成 wall segments（从 edges）
        List<StructuralSkeleton.WallSegment> walls = generateWallSegments(planSkeleton, floorPlate);

        // 3. 生成 courtyard voids（从 courtyards）
        List<StructuralSkeleton.CourtyardVoid> courtyards = generateCourtyardVoids(planSkeleton);

        // 4. 生成 alignment constraints（从 axes）
        List<StructuralSkeleton.AxisConstraint> axes = generateAlignmentConstraints(planSkeleton);

        return new StructuralSkeleton(floorPlate, walls, courtyards, axes);
    }

    /**
     * 生成 floor plate（规则 1）
     * <p>
     * v1 简化：如果 outline 没有具体几何信息，使用默认矩形
     * 未来：可以从 outline 的几何数据生成 polygon
     */
    private static StructuralSkeleton.FloorPlate generateFloorPlate(PlanSkeleton planSkeleton) {
        // v1 简化：使用默认矩形（10x10，中心在原点）
        // 未来：从 outline 或 zones 的实际几何数据生成 polygon
        Polygon2D footprint = Polygon2D.rectangle(
                new Vec2(-5, -5),
                new Vec2(5, 5)
        );

        return new StructuralSkeleton.FloorPlate(
                footprint,
                DEFAULT_FLOOR_Y,
                DEFAULT_FLOOR_THICKNESS,
                GroundingMode.FLAT
        );
    }

    /**
     * 生成 wall segments（规则 2）
     * <p>
     * 映射：
     * - external_wall → EXTERNAL
     * - shared_wall → INTERNAL
     * - courtyard_wall → COURTYARD
     */
    private static List<StructuralSkeleton.WallSegment> generateWallSegments(
            PlanSkeleton planSkeleton,
            StructuralSkeleton.FloorPlate floorPlate
    ) {
        List<StructuralSkeleton.WallSegment> segments = new ArrayList<>();

        if (planSkeleton.edges() == null) {
            return segments;
        }

        int segmentCounter = 1;

        for (PlanSkeleton.Edge edge : planSkeleton.edges()) {
            if (edge == null || edge.id() == null) {
                continue;
            }

            // 决定 WallType
            WallType wallType;
            if ("external_wall".equalsIgnoreCase(edge.type())) {
                wallType = WallType.EXTERNAL;
            } else if ("courtyard_wall".equalsIgnoreCase(edge.type())) {
                wallType = WallType.COURTYARD;
            } else if ("shared_wall".equalsIgnoreCase(edge.type())) {
                wallType = WallType.INTERNAL;
            } else {
                // boundary_edge 等，v1 跳过
                continue;
            }

            // v1 简化：生成默认基线（从 edge 推断，如果没有具体几何，使用默认）
            // 未来：从 edge 的实际几何数据生成 baseLine
            Polyline2D baseline = generateWallBaseLine(edge, segmentCounter);

            // 决定 heightProfile
            double baseY = floorPlate != null ? floorPlate.baseY : DEFAULT_FLOOR_Y;
            HeightProfile heightProfile = HeightProfile.fixed(baseY, DEFAULT_WALL_HEIGHT);

            // 决定 normal（v1 简化：EXTERNAL → OUTWARD，其他 → 向内）
            Vector2 normal = computeWallNormal(baseline, wallType);

            // 提取 zones
            List<String> zones = edge.zones() != null ? new ArrayList<>(edge.zones()) : new ArrayList<>();

            segments.add(new StructuralSkeleton.WallSegment(
                    "wall_" + segmentCounter++,
                    wallType,
                    baseline,
                    DEFAULT_WALL_THICKNESS,
                    heightProfile,
                    normal,
                    zones
            ));
        }

        return segments;
    }

    /**
     * 生成墙基线（v1 简化）
     * <p>
     * v1：使用默认基线（5格长度的墙段）
     * 未来：从 edge 的实际几何数据生成
     */
    private static Polyline2D generateWallBaseLine(PlanSkeleton.Edge edge, int index) {
        // v1 简化：根据 edge 的类型和 zones 生成一个默认基线
        // 这里我们生成一个简单的直线墙段，不同 index 生成不同位置
        // 未来：需要实际的几何计算
        
        // 临时实现：根据 index 生成不同位置的墙段
        Vec2 start = new Vec2(index * 6 - 3, -5);
        Vec2 end = new Vec2(index * 6 - 3, 5);
        return Polyline2D.line(start, end);
    }

    /**
     * 计算墙的法线方向
     */
    private static Vector2 computeWallNormal(Polyline2D baseline, WallType wallType) {
        Vec2 start = baseline.getStart();
        Vec2 end = baseline.getEnd();
        if (start == null || end == null) {
            return Vector2.ZERO;
        }

        // 计算方向向量
        Vec2 dir = end.subtract(start);
        Vec2 normalVec = dir.rotate90().normalize();

        // 根据墙类型决定法线方向
        // EXTERNAL: 向外（法线指向外侧）
        // INTERNAL/COURTYARD: 向内（法线指向内侧）
        if (wallType == WallType.EXTERNAL) {
            // 向外
            return Vector2.from(normalVec);
        } else {
            // 向内（反向）
            return Vector2.from(normalVec.scale(-1));
        }
    }

    /**
     * 生成 courtyard voids（规则 3）
     */
    private static List<StructuralSkeleton.CourtyardVoid> generateCourtyardVoids(PlanSkeleton planSkeleton) {
        List<StructuralSkeleton.CourtyardVoid> voids = new ArrayList<>();

        if (planSkeleton.courtyards() == null) {
            return voids;
        }

        for (PlanSkeleton.Courtyard courtyard : planSkeleton.courtyards()) {
            if (courtyard == null || courtyard.id() == null) {
                continue;
            }

            // v1 简化：生成默认多边形（3x3 正方形）
            // 未来：从 courtyard 的实际几何数据生成 polygon
            Polygon2D footprint = Polygon2D.rectangle(
                    new Vec2(-1, -1),
                    new Vec2(1, 1)
            );

            voids.add(new StructuralSkeleton.CourtyardVoid(
                    footprint,
                    true,  // open_to_sky
                    courtyard.adjacentZones() != null ? new ArrayList<>(courtyard.adjacentZones()) : new ArrayList<>()
            ));
        }

        return voids;
    }

    /**
     * 生成 alignment constraints（规则 4）
     * <p>
     * 从 axes 生成对齐约束
     */
    private static List<StructuralSkeleton.AxisConstraint> generateAlignmentConstraints(PlanSkeleton planSkeleton) {
        List<StructuralSkeleton.AxisConstraint> constraints = new ArrayList<>();

        if (planSkeleton.axes() == null) {
            return constraints;
        }

        for (PlanSkeleton.Axis axis : planSkeleton.axes()) {
            if (axis == null || axis.id() == null) {
                continue;
            }

            // 决定 role
            AxisRole role;
            if ("primary".equalsIgnoreCase(axis.role())) {
                role = AxisRole.PRIMARY;
            } else {
                role = AxisRole.SECONDARY;
            }

            // v1 简化：生成默认轴线（东西方向，通过原点）
            // 未来：从 axis 的实际几何数据生成 Line2D
            Line2D axisLine = new Line2D(
                    new Vec2(-10, 0),
                    new Vec2(10, 0)
            );

            constraints.add(new StructuralSkeleton.AxisConstraint(
                    axis.id(),
                    axisLine,
                    role,
                    axis.zones() != null ? new ArrayList<>(axis.zones()) : new ArrayList<>()
            ));
        }

        return constraints;
    }
}
