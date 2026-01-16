package com.formacraft.common.geometry.extrusion;

import com.formacraft.common.geometry.*;
import com.formacraft.common.llm.dto.StructuralSkeleton;
import com.formacraft.common.llm.dto.structural.HeightProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * WallExtrusion（墙段拉伸算法）
 * <p>
 * 核心职责：将 WallSegment 转换为 ExtrudedSolid
 * <p>
 * 算法：
 * 1. 在 XZ 平面上对 baseline 做"厚度偏移"
 * 2. 在 Y 方向拉伸到指定高度
 * <p>
 * 策略：
 * - 先支持直线墙段（v1 必须）
 * - 再支持折线墙段（逐段 extrusion + 节点拼接）
 */
public final class WallExtrusion {

    private WallExtrusion() {}

    /**
     * 将 WallSegment 拉伸为 ExtrudedSolid
     * 
     * @param wall WallSegment
     * @return ExtrudedSolid 列表（折线墙可能返回多个）
     */
    public static List<ExtrudedSolid> extrude(StructuralSkeleton.WallSegment wall) {
        if (wall == null || wall.baseline == null) {
            return List.of();
        }

        List<Vec2> points = wall.baseline.getPoints();
        if (points.size() < 2) {
            return List.of();
        }

        List<ExtrudedSolid> solids = new ArrayList<>();

        // 如果是直线（只有 2 个点），直接处理
        if (points.size() == 2) {
            ExtrudedSolid solid = extrudeSingleSegment(
                    points.get(0),
                    points.get(1),
                    wall.thickness,
                    wall.heightProfile,
                    wall.normal
            );
            if (solid != null && !solid.isEmpty()) {
                solids.add(solid);
            }
        } else {
            // 折线：逐段处理
            for (int i = 0; i < points.size() - 1; i++) {
                ExtrudedSolid solid = extrudeSingleSegment(
                        points.get(i),
                        points.get(i + 1),
                        wall.thickness,
                        wall.heightProfile,
                        wall.normal
                );
                if (solid != null && !solid.isEmpty()) {
                    solids.add(solid);
                }
            }
        }

        return solids;
    }

    /**
     * 拉伸单个线段（直线墙段的核心算法）
     * <p>
     * 输入：P0 -------- P1
     * <p>
     * Step 1: 计算偏移线（墙厚）
     * Step 2: 拉伸到 3D（高度）
     * Step 3: 构造面（6 个面）
     */
    private static ExtrudedSolid extrudeSingleSegment(
            Vec2 p0,
            Vec2 p1,
            double thickness,
            HeightProfile heightProfile,
            Vector2 normal
    ) {
        if (heightProfile == null) {
            return null;
        }

        // Step 1: 计算偏移线的 4 个 2D 点
        // normal 已经由 PlanSkeleton 提供，指向 exterior / courtyard
        Vec2 n = normal.toVec2();
        double offset = thickness / 2.0;

        Vec2 a = p0.add(n.scale(offset));  // P0 + n * (t/2)
        Vec2 b = p1.add(n.scale(offset));  // P1 + n * (t/2)
        Vec2 c = p1.subtract(n.scale(offset)); // P1 - n * (t/2)
        Vec2 d = p0.subtract(n.scale(offset)); // P0 - n * (t/2)

        // Step 2: 拉伸到 3D（高度）
        double y0 = heightProfile.baseY;
        double y1 = heightProfile.topY;

        // 生成 8 个 3D 点
        Vec3 a0 = Vec3.from2D(a, y0);
        Vec3 b0 = Vec3.from2D(b, y0);
        Vec3 c0 = Vec3.from2D(c, y0);
        Vec3 d0 = Vec3.from2D(d, y0);
        Vec3 a1 = Vec3.from2D(a, y1);
        Vec3 b1 = Vec3.from2D(b, y1);
        Vec3 c1 = Vec3.from2D(c, y1);
        Vec3 d1 = Vec3.from2D(d, y1);

        List<Vec3> vertices = List.of(a0, b0, c0, d0, a1, b1, c1, d1);

        // Step 3: 构造面（6 个面）
        List<Face> faces = new ArrayList<>();

        // 外立面: A0 → B0 → B1 → A1
        faces.add(new Face(List.of(a0, b0, b1, a1)));

        // 内立面: D0 → C0 → C1 → D1
        faces.add(new Face(List.of(d0, c0, c1, d1)));

        // 顶面: A1 → B1 → C1 → D1
        faces.add(new Face(List.of(a1, b1, c1, d1)));

        // 底面: D0 → C0 → B0 → A0
        faces.add(new Face(List.of(d0, c0, b0, a0)));

        // 端面1: A0 → D0 → D1 → A1
        faces.add(new Face(List.of(a0, d0, d1, a1)));

        // 端面2: B0 → C0 → C1 → B1
        faces.add(new Face(List.of(b0, c0, c1, b1)));

        return new ExtrudedSolid(vertices, faces);
    }
}
