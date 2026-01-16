package com.formacraft.common.geometry.boolean_;

import com.formacraft.common.geometry.*;
import com.formacraft.common.llm.dto.StructuralSkeleton;
import com.formacraft.common.llm.dto.structural.HeightProfile;
import com.formacraft.common.llm.dto.structural.WallType;

import java.util.ArrayList;
import java.util.List;

/**
 * FloorCourtyardBooleanProcessor（FloorPlate / Courtyard Boolean 处理器）
 * <p>
 * 核心职责：执行 FloorPlate 和 CourtyardVoid 的 Boolean 运算，并从中派生墙体
 * <p>
 * 核心思想：
 * - FloorPlate = 主体实心体量（Solid）
 * - CourtyardVoid = 从 FloorPlate 中减去的负体量（Void）
 * - 墙体永远沿"实—空"的交界线生成
 * <p>
 * v1 策略：
 * - 在 2D 平面（XZ）做 Boolean
 * - 在 3D 只做 Extrusion
 * - 从 Boolean 结果派生外墙和庭院墙
 */
public final class FloorCourtyardBooleanProcessor {

    private FloorCourtyardBooleanProcessor() {}

    /**
     * 处理 FloorPlate 和 CourtyardVoid 的 Boolean 运算，生成墙体
     * <p>
     * 流程：
     * 1. FloorPlate.footprint - CourtyardVoid.footprint(s) → EffectiveFootprint
     * 2. 从 EffectiveFootprint 外边界生成外墙
     * 3. 从 CourtyardVoid 边界生成庭院墙
     * 
     * @param floorPlate FloorPlate
     * @param courtyards CourtyardVoid 列表
     * @param wallHeight 墙高（用于 HeightProfile）
     * @param wallThickness 墙厚
     * @return 生成的 WallSegment 列表
     */
    public static List<StructuralSkeleton.WallSegment> processBooleanAndGenerateWalls(
            StructuralSkeleton.FloorPlate floorPlate,
            List<StructuralSkeleton.CourtyardVoid> courtyards,
            double wallHeight,
            double wallThickness
    ) {
        List<StructuralSkeleton.WallSegment> walls = new ArrayList<>();

        if (floorPlate == null || floorPlate.footprint == null) {
            return walls;
        }

        Polygon2D baseFootprint = floorPlate.footprint;
        List<Polygon2D> holeFootprints = new ArrayList<>();
        if (courtyards != null) {
            for (StructuralSkeleton.CourtyardVoid courtyard : courtyards) {
                if (courtyard != null && courtyard.footprint != null) {
                    holeFootprints.add(courtyard.footprint);
                }
            }
        }

        // Step 1: 2D Boolean 运算
        PolygonBooleanResult booleanResult = PolygonBoolean.subtract(baseFootprint, holeFootprints);

        // Step 2: 从外边界生成外墙
        for (Polygon2D outerBoundary : booleanResult.getOuterBoundaries()) {
            List<StructuralSkeleton.WallSegment> externalWalls = generateExternalWalls(
                    outerBoundary,
                    floorPlate,
                    wallHeight,
                    wallThickness
            );
            walls.addAll(externalWalls);
        }

        // Step 3: 从洞边界生成庭院墙
        for (Polyline2D holeBoundary : booleanResult.getHoleBoundaries()) {
            StructuralSkeleton.WallSegment courtyardWall = generateCourtyardWall(
                    holeBoundary,
                    floorPlate,
                    courtyards,
                    wallHeight,
                    wallThickness
            );
            if (courtyardWall != null) {
                walls.add(courtyardWall);
            }
        }

        return walls;
    }

    /**
     * 从外边界生成外墙
     * <p>
     * 策略：将 polygon 的每条边转换为一个 WallSegment
     */
    private static List<StructuralSkeleton.WallSegment> generateExternalWalls(
            Polygon2D outerBoundary,
            StructuralSkeleton.FloorPlate floorPlate,
            double wallHeight,
            double wallThickness
    ) {
        List<StructuralSkeleton.WallSegment> walls = new ArrayList<>();

        if (outerBoundary == null || outerBoundary.getVertices().size() < 3) {
            return walls;
        }

        List<Vec2> vertices = outerBoundary.getVertices();
        int wallCounter = 1;

        // 将 polygon 的每条边转换为一个 WallSegment
        for (int i = 0; i < vertices.size(); i++) {
            Vec2 start = vertices.get(i);
            Vec2 end = vertices.get((i + 1) % vertices.size());

            Polyline2D baseline = Polyline2D.line(start, end);

            // 计算法线（向外）
            Vec2 dir = end.subtract(start);
            Vec2 normalVec = dir.rotate90().normalize();
            Vector2 normal = Vector2.from(normalVec);

            // 创建 HeightProfile
            HeightProfile heightProfile = HeightProfile.fixed(floorPlate.baseY, wallHeight);

            walls.add(new StructuralSkeleton.WallSegment(
                    "external_wall_" + wallCounter++,
                    WallType.EXTERNAL,
                    baseline,
                    wallThickness,
                    heightProfile,
                    normal,
                    List.of() // zones 在后续步骤中关联
            ));
        }

        return walls;
    }

    /**
     * 从洞边界生成庭院墙
     * <p>
     * 注意：normal 方向向内（指向 courtyard）
     */
    private static StructuralSkeleton.WallSegment generateCourtyardWall(
            Polyline2D holeBoundary,
            StructuralSkeleton.FloorPlate floorPlate,
            List<StructuralSkeleton.CourtyardVoid> courtyards,
            double wallHeight,
            double wallThickness
    ) {
        if (holeBoundary == null || holeBoundary.getPoints().size() < 2) {
            return null;
        }

        // 计算法线（向内，指向 courtyard）
        Vec2 start = holeBoundary.getStart();
        Vec2 end = holeBoundary.getEnd();
        if (start == null || end == null) {
            return null;
        }

        Vec2 dir = end.subtract(start);
        Vec2 normalVec = dir.rotate90().normalize();
        // 向内：反向
        Vector2 normal = Vector2.from(normalVec.scale(-1));

        // 创建 HeightProfile
        HeightProfile heightProfile = HeightProfile.fixed(floorPlate.baseY, wallHeight);

        // 查找对应的 courtyard（用于关联 zones）
        List<String> zones = new ArrayList<>();
        if (courtyards != null) {
            // v1 简化：使用第一个 courtyard 的 adjacentZones
            // 未来：可以更精确地匹配 holeBoundary 和 courtyard
            for (StructuralSkeleton.CourtyardVoid courtyard : courtyards) {
                if (courtyard != null && !courtyard.adjacentZones.isEmpty()) {
                    zones.addAll(courtyard.adjacentZones);
                    break;
                }
            }
        }

        return new StructuralSkeleton.WallSegment(
                "courtyard_wall_1",
                WallType.COURTYARD,
                holeBoundary,
                wallThickness,
                heightProfile,
                normal,
                zones
        );
    }

    /**
     * 从 polygon 提取所有边界段（用于生成多个 WallSegment）
     * <p>
     * 如果 polygon 有多个洞，每个洞的边界都会生成独立的 WallSegment
     */
    public static List<Polyline2D> extractBoundarySegments(Polygon2D polygon) {
        List<Polyline2D> segments = new ArrayList<>();

        if (polygon == null || polygon.getVertices().size() < 3) {
            return segments;
        }

        List<Vec2> vertices = polygon.getVertices();
        for (int i = 0; i < vertices.size(); i++) {
            Vec2 start = vertices.get(i);
            Vec2 end = vertices.get((i + 1) % vertices.size());
            segments.add(Polyline2D.line(start, end));
        }

        return segments;
    }
}
