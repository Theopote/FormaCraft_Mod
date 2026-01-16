package com.formacraft.common.llm.converter;

import com.formacraft.common.llm.dto.PlanSkeleton;
import com.formacraft.common.llm.dto.StructuralSkeleton;
import net.minecraft.util.math.BlockPos;

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
 * 5. roof → ROOF_PLATE（v1 简化：统一屋顶）
 * <p>
 * 设计原则：
 * - 不关心风格、不关心构件，只关心结构
 * - 保守转换：优先生成合理的默认值
 * - 可扩展：为未来更复杂的几何推断预留接口
 */
public final class PlanSkeletonToStructuralSkeletonConverter {

    private PlanSkeletonToStructuralSkeletonConverter() {}

    // 默认值（v1 简化）
    private static final int DEFAULT_WALL_HEIGHT = 5;
    private static final int DEFAULT_FLOOR_Y = 0;
    private static final int DEFAULT_ROOF_THICKNESS = 1;

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
        List<StructuralSkeleton.WallSegment> wallSegments = generateWallSegments(planSkeleton);

        // 3. 生成 courtyard voids（从 courtyards）
        List<StructuralSkeleton.CourtyardVoid> courtyardVoids = generateCourtyardVoids(planSkeleton);

        // 4. 生成 roof plate（v1 简化：统一屋顶）
        StructuralSkeleton.RoofPlate roofPlate = generateRoofPlate(planSkeleton, floorPlate);

        // 5. 生成 alignment constraints（从 axes）
        List<StructuralSkeleton.AlignmentConstraint> alignmentConstraints = generateAlignmentConstraints(planSkeleton);

        return new StructuralSkeleton(
                "formacraft.structural_skeleton.v1",
                floorPlate,
                wallSegments,
                courtyardVoids,
                roofPlate,
                alignmentConstraints
        );
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
        List<BlockPos> polygonXZ = List.of(
                new BlockPos(-5, 0, -5),
                new BlockPos(5, 0, -5),
                new BlockPos(5, 0, 5),
                new BlockPos(-5, 0, 5)
        );

        return new StructuralSkeleton.FloorPlate(
                polygonXZ,
                DEFAULT_FLOOR_Y
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
    private static List<StructuralSkeleton.WallSegment> generateWallSegments(PlanSkeleton planSkeleton) {
        List<StructuralSkeleton.WallSegment> segments = new ArrayList<>();

        if (planSkeleton.edges() == null) {
            return segments;
        }

        int segmentCounter = 1;

        for (PlanSkeleton.Edge edge : planSkeleton.edges()) {
            if (edge == null || edge.id() == null) {
                continue;
            }

            // 决定 kind
            StructuralSkeleton.WallSegment.Kind kind;
            if ("external_wall".equalsIgnoreCase(edge.type())) {
                kind = StructuralSkeleton.WallSegment.Kind.EXTERNAL;
            } else if ("courtyard_wall".equalsIgnoreCase(edge.type())) {
                kind = StructuralSkeleton.WallSegment.Kind.COURTYARD;
            } else if ("shared_wall".equalsIgnoreCase(edge.type())) {
                kind = StructuralSkeleton.WallSegment.Kind.INTERNAL;
            } else {
                // boundary_edge 等，v1 跳过
                continue;
            }

            // v1 简化：生成默认基线（从 edge 推断，如果没有具体几何，使用默认）
            // 未来：从 edge 的实际几何数据生成 baseLine
            List<BlockPos> baseLine = generateWallBaseLine(edge, kind);

            // 决定 height（v1 使用默认值）
            int height = DEFAULT_WALL_HEIGHT;

            // 决定 normal（v1 简化：EXTERNAL → OUTWARD，其他 → 未定义）
            String normal = (kind == StructuralSkeleton.WallSegment.Kind.EXTERNAL) ? "OUTWARD" : null;

            segments.add(new StructuralSkeleton.WallSegment(
                    "wall_" + segmentCounter++,
                    kind.name(),
                    baseLine,
                    height,
                    normal,
                    edge.zones() != null ? new ArrayList<>(edge.zones()) : new ArrayList<>()
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
    private static List<BlockPos> generateWallBaseLine(PlanSkeleton.Edge edge, StructuralSkeleton.WallSegment.Kind kind) {
        // v1 简化：根据 edge 的类型和 zones 生成一个默认基线
        // 这里我们生成一个简单的直线墙段
        // 未来：需要实际的几何计算
        
        // 临时实现：生成一个5格长度的墙段（东西方向）
        return List.of(
                new BlockPos(0, DEFAULT_FLOOR_Y, 0),
                new BlockPos(5, DEFAULT_FLOOR_Y, 0)
        );
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
            List<BlockPos> polygonXZ = List.of(
                    new BlockPos(-1, DEFAULT_FLOOR_Y, -1),
                    new BlockPos(1, DEFAULT_FLOOR_Y, -1),
                    new BlockPos(1, DEFAULT_FLOOR_Y, 1),
                    new BlockPos(-1, DEFAULT_FLOOR_Y, 1)
            );

            voids.add(new StructuralSkeleton.CourtyardVoid(
                    courtyard.id(),
                    polygonXZ,
                    true,  // open_to_sky
                    courtyard.adjacentZones() != null ? new ArrayList<>(courtyard.adjacentZones()) : new ArrayList<>()
            ));
        }

        return voids;
    }

    /**
     * 生成 roof plate（规则 5）
     * <p>
     * v1 简化：统一屋顶（从 floor plate 推断，向内偏移 1 格）
     */
    private static StructuralSkeleton.RoofPlate generateRoofPlate(
            PlanSkeleton planSkeleton,
            StructuralSkeleton.FloorPlate floorPlate
    ) {
        if (floorPlate == null || floorPlate.polygonXZ() == null || floorPlate.polygonXZ().isEmpty()) {
            return null;
        }

        // v1 简化：从 floor plate 生成屋顶（向内偏移 1 格）
        // 未来：可以从 outline 或 zones 的实际几何数据生成
        List<BlockPos> roofPolygon = new ArrayList<>();
        for (BlockPos pos : floorPlate.polygonXZ()) {
            // 向内偏移（简化：每个方向向内 1 格）
            roofPolygon.add(pos.add(-1, 0, -1));
        }

        int roofY = floorPlate.baseY() + DEFAULT_WALL_HEIGHT;

        return new StructuralSkeleton.RoofPlate(
                roofPolygon,
                roofY,
                DEFAULT_ROOF_THICKNESS
        );
    }

    /**
     * 生成 alignment constraints（规则 4）
     * <p>
     * 从 axes 生成对齐约束
     */
    private static List<StructuralSkeleton.AlignmentConstraint> generateAlignmentConstraints(PlanSkeleton planSkeleton) {
        List<StructuralSkeleton.AlignmentConstraint> constraints = new ArrayList<>();

        if (planSkeleton.axes() == null) {
            return constraints;
        }

        for (PlanSkeleton.Axis axis : planSkeleton.axes()) {
            if (axis == null || axis.id() == null) {
                continue;
            }

            // 决定 role
            StructuralSkeleton.AlignmentConstraint.Role role;
            if ("primary".equalsIgnoreCase(axis.role())) {
                role = StructuralSkeleton.AlignmentConstraint.Role.primary;
            } else if ("symmetry".equalsIgnoreCase(axis.role())) {
                role = StructuralSkeleton.AlignmentConstraint.Role.symmetry;
            } else {
                role = StructuralSkeleton.AlignmentConstraint.Role.secondary;
            }

            // 生成 preferences
            StructuralSkeleton.AlignmentConstraint.AlignmentPreferences preferences =
                    new StructuralSkeleton.AlignmentConstraint.AlignmentPreferences(
                            role == StructuralSkeleton.AlignmentConstraint.Role.primary ? 1.0 : 0.5,  // straightness
                            role == StructuralSkeleton.AlignmentConstraint.Role.primary,  // orthogonal
                            role == StructuralSkeleton.AlignmentConstraint.Role.symmetry  // symmetry
                    );

            constraints.add(new StructuralSkeleton.AlignmentConstraint(
                    axis.id(),
                    role.name(),
                    axis.zones() != null ? new ArrayList<>(axis.zones()) : new ArrayList<>(),
                    preferences
            ));
        }

        return constraints;
    }
}
