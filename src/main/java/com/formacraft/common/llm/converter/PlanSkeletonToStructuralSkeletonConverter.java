package com.formacraft.common.llm.converter;

import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.geometry.*;
import com.formacraft.common.geometry.boolean_.FloorCourtyardBooleanProcessor;
import com.formacraft.common.geometry.boolean_.RoofPlateGenerator;
import com.formacraft.common.llm.dto.PlanSkeleton;
import com.formacraft.common.llm.dto.StructuralSkeleton;
import com.formacraft.common.llm.dto.structural.*;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * PlanSkeleton → StructuralSkeleton 转换器
 * <p>
 * 🎯 架构校准后（2026-01-14）：
 * 此转换器当前执行的是"简化流程"，将 PlanSkeleton（Domain）直接转换为 StructuralSkeleton（候选结构）。
 * <p>
 * ⚠️ 重要说明：
 * 正确的架构应该是：
 * ```
 * PlanSkeleton (Domain)
 *   ↓
 * Building Mass Assembly (体量组合)
 *   ↓
 * StructuralSkeleton (从体量组合派生)
 * ```
 * <p>
 * 当前流程（简化版本）：
 * ```
 * PlanSkeleton (Domain) → StructuralSkeleton (候选生成)
 * ```
 * <p>
 * 这意味着：
 * - ✅ 生成的 StructuralSkeleton 应该被视为"候选结构模板"
 * - ✅ 不是"必然实例化"的结构
 * - ✅ 真正的结构应该从"体量组合"后派生
 * <p>
 * 未来方向：
 * - 将 StructuralSkeleton 的生成移到 Building Mass Assembly 之后
 * - 让转换器成为"体量组合 → 结构派生"的工具
 * <p>
 * 当前职责（简化版本）：
 * 将 PlanSkeleton（Domain）转换为 StructuralSkeleton（候选结构模板）
 * <p>
 * 映射规则（候选生成）：
 * 1. outline → FLOOR_PLATE（候选地面板）
 * 2. edges → WALL_SEGMENT（候选墙段）
 *    - external_wall → EXTERNAL
 *    - shared_wall → INTERNAL
 *    - courtyard_wall → COURTYARD
 * 3. courtyards → COURTYARD_VOID（候选庭院空洞）
 * 4. axes → AlignmentConstraint（对齐约束）
 * <p>
 * 设计原则：
 * - 不关心风格、不关心构件，只关心结构语义
 * - 保守转换：优先生成合理的默认值
 * - 可扩展：为未来更复杂的几何推断预留接口
 * - ⚠️ 生成的都是"候选"，等待体量组合后的实例化
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
     * <p>
     * 核心流程：
     * 1. 生成 FloorPlate（主体实心体量）
     * 2. 生成 CourtyardVoid（负体量）
     * 3. 执行 Boolean 运算，从边界派生墙体
     * 4. 生成 alignment constraints
     * 
     * @param planSkeleton PlanSkeleton（2D 几何语义）
     * @return StructuralSkeleton（3D 结构骨架）
     */
    public static StructuralSkeleton convert(PlanSkeleton planSkeleton) {
        return convert(planSkeleton, null);
    }

    /**
     * C1：带 outline 的转换。当提供了用户/系统的 {@link OutlineShape} 时，从其真实几何生成
     * 多边形楼板，替代早期写死的 10×10 矩形；未提供时保持原默认行为。
     */
    public static StructuralSkeleton convert(PlanSkeleton planSkeleton, OutlineShape outline) {
        if (planSkeleton == null) {
            throw new IllegalArgumentException("PlanSkeleton cannot be null");
        }

        // 1. 生成 floor plate（优先用 outline 真实多边形，否则回退默认矩形）
        StructuralSkeleton.FloorPlate floorPlate = generateFloorPlate(planSkeleton, outline);

        // 2. 生成 courtyard voids（从 courtyards）
        List<StructuralSkeleton.CourtyardVoid> courtyards = generateCourtyardVoids(planSkeleton);

        // 3. 执行 Boolean 运算，从边界派生墙体（核心）
        List<StructuralSkeleton.WallSegment> booleanWalls = 
                FloorCourtyardBooleanProcessor.processBooleanAndGenerateWalls(
                        floorPlate,
                        courtyards,
                        DEFAULT_WALL_HEIGHT,
                        DEFAULT_WALL_THICKNESS
                );

        // 4. 从 edges 生成额外的墙（shared_wall 等，这些不从 Boolean 派生）
        List<StructuralSkeleton.WallSegment> edgeWalls = generateWallSegmentsFromEdges(planSkeleton, floorPlate);

        // 合并墙体
        List<StructuralSkeleton.WallSegment> allWalls = new ArrayList<>(booleanWalls);
        allWalls.addAll(edgeWalls);

        // 5. 生成 roof plate（从 Boolean 结果）
        StructuralSkeleton.RoofPlate roofPlate = RoofPlateGenerator.generateRoofPlate(
                floorPlate,
                courtyards,
                DEFAULT_WALL_HEIGHT
        );

        // 6. 生成 alignment constraints（从 axes）
        List<StructuralSkeleton.AxisConstraint> axes = generateAlignmentConstraints(planSkeleton);

        return new StructuralSkeleton(floorPlate, allWalls, courtyards, roofPlate, axes);
    }

    /**
     * 生成 floor plate（规则 1）
     * <p>
     * v1 简化：如果 outline 没有具体几何信息，使用默认矩形
     * 未来：可以从 outline 的几何数据生成 polygon
     */
    private static StructuralSkeleton.FloorPlate generateFloorPlate(PlanSkeleton planSkeleton, OutlineShape outline) {
        // C1：优先从 outline 真实几何生成多边形楼板；无 outline 时回退默认 10×10 矩形。
        Polygon2D footprint = polygonFromOutline(outline);
        if (footprint == null || footprint.vertexCount() < 3) {
            footprint = Polygon2D.rectangle(new Vec2(-5, -5), new Vec2(5, 5));
        }

        return new StructuralSkeleton.FloorPlate(
                footprint,
                DEFAULT_FLOOR_Y,
                DEFAULT_FLOOR_THICKNESS,
                GroundingMode.FLAT
        );
    }

    /**
     * 从 {@link OutlineShape} 构造原点居中的 {@link Polygon2D}（世界 XZ → 以质心为原点的平面局部坐标）。
     * 支持多边形顶点与圆（用规则多边形近似）。无法构造时返回 null 由调用方回退。
     */
    private static Polygon2D polygonFromOutline(OutlineShape outline) {
        if (outline == null) {
            return null;
        }
        List<BlockPos> verts = outline.vertices();
        if (verts != null && verts.size() >= 3) {
            double cx = 0, cz = 0;
            for (BlockPos p : verts) {
                cx += p.getX() + 0.5;
                cz += p.getZ() + 0.5;
            }
            cx /= verts.size();
            cz /= verts.size();
            List<Vec2> pts = new ArrayList<>(verts.size());
            for (BlockPos p : verts) {
                pts.add(new Vec2((p.getX() + 0.5) - cx, (p.getZ() + 0.5) - cz));
            }
            return new Polygon2D(pts);
        }
        if ("circle".equalsIgnoreCase(outline.shapeType()) && outline.radius() > 0) {
            int r = outline.radius();
            int seg = Math.max(8, Math.min(48, r * 2));
            List<Vec2> pts = new ArrayList<>(seg);
            for (int i = 0; i < seg; i++) {
                double a = (2.0 * Math.PI * i) / seg;
                pts.add(new Vec2(Math.cos(a) * r, Math.sin(a) * r));
            }
            return new Polygon2D(pts);
        }
        return null;
    }

    /**
     * 从 edges 生成墙段（shared_wall 等，不从 Boolean 派生）
     * <p>
     * 注意：external_wall 和 courtyard_wall 应该从 Boolean 运算派生，
     * 这里只处理 shared_wall（内墙）
     */
    private static List<StructuralSkeleton.WallSegment> generateWallSegmentsFromEdges(
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
            // 注意：external_wall 和 courtyard_wall 应该从 Boolean 运算派生
            // 这里只处理 shared_wall（内墙）
            WallType wallType;
            if ("shared_wall".equalsIgnoreCase(edge.type())) {
                wallType = WallType.INTERNAL;
            } else {
                // external_wall 和 courtyard_wall 跳过（从 Boolean 派生）
                // boundary_edge 等也跳过
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
