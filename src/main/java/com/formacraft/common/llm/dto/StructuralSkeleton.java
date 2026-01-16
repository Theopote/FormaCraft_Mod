package com.formacraft.common.llm.dto;

import com.formacraft.common.geometry.*;
import com.formacraft.common.llm.dto.structural.*;

import java.util.List;

/**
 * StructuralSkeleton（3D 结构骨架）v1
 * <p>
 * 核心定义：StructuralSkeleton 是 PlanSkeleton → ArchitecturalSkeleton 的中间层。
 * <p>
 * 职责：不关心风格、不关心构件，只关心：
 * - 墙在哪里
 * - 墙是内还是外
 * - 墙有多高
 * - 地面 / 屋顶是否存在
 * <p>
 * 设计原则：
 * 1. 只描述"连续几何"，不描述 block
 * 2. XZ 是主维度，Y 只描述高度策略
 * 3. 所有元素必须能推导出：边、面、内外法向
 * 4. 必须能被可视化（debug overlay）
 * 5. 必须能一对一映射到 Skeleton
 */
public class StructuralSkeleton {
    public final FloorPlate floorPlate;
    public final List<WallSegment> walls;
    public final List<CourtyardVoid> courtyards;
    public final List<AxisConstraint> axes;

    public StructuralSkeleton(
            FloorPlate floorPlate,
            List<WallSegment> walls,
            List<CourtyardVoid> courtyards,
            List<AxisConstraint> axes
    ) {
        this.floorPlate = floorPlate;
        this.walls = walls != null ? List.copyOf(walls) : List.of();
        this.courtyards = courtyards != null ? List.copyOf(courtyards) : List.of();
        this.axes = axes != null ? List.copyOf(axes) : List.of();
    }

    /**
     * FloorPlate（地面板）
     * <p>
     * 一切的"几何基底"
     * <p>
     * floor plate 是唯一必定存在的元素
     * <p>
     * ⚠️ 注意：floor plate ≠ 房间，它只是承载墙和体量的"地基"
     */
    public static class FloorPlate {
        /** 闭合多边形（XZ 平面） */
        public final Polygon2D footprint;

        /** 地基参考高度 */
        public final double baseY;

        /** 厚度（结构厚度，不是最终方块） */
        public final double thickness;

        /** 与 terrain 的贴合方式 */
        public final GroundingMode groundingMode;

        public FloorPlate(
                Polygon2D footprint,
                double baseY,
                double thickness,
                GroundingMode groundingMode
        ) {
            this.footprint = footprint;
            this.baseY = baseY;
            this.thickness = thickness;
            this.groundingMode = groundingMode != null ? groundingMode : GroundingMode.FLAT;
        }
    }

    /**
     * WallSegment（墙段）
     * <p>
     * 核心中的核心，90% 的建筑"感觉"来自墙
     */
    public static class WallSegment {
        /** 唯一 ID（用于 graph / debug） */
        public final String id;

        /** 墙体类型 */
        public final WallType type;

        /** XZ 平面中的基线（有方向） */
        public final Polyline2D baseline;

        /** 墙厚（结构厚度） */
        public final double thickness;

        /** 墙体高度策略 */
        public final HeightProfile heightProfile;

        /** 墙体法向（指向 exterior 或 courtyard） */
        public final Vector2 normal;

        /** 关联的 zone（一个或两个） */
        public final List<String> zones;

        public WallSegment(
                String id,
                WallType type,
                Polyline2D baseline,
                double thickness,
                HeightProfile heightProfile,
                Vector2 normal,
                List<String> zones
        ) {
            this.id = id;
            this.type = type;
            this.baseline = baseline;
            this.thickness = thickness;
            this.heightProfile = heightProfile;
            this.normal = normal;
            this.zones = zones != null ? List.copyOf(zones) : List.of();
        }

        /**
         * 判断是否是外墙
         */
        public boolean isExterior() {
            return type == WallType.EXTERNAL;
        }

        /**
         * 判断是否是庭院墙
         */
        public boolean isCourtyard() {
            return type == WallType.COURTYARD;
        }
    }

    /**
     * CourtyardVoid（庭院空洞）
     * <p>
     * "负体量"的几何实体
     * <p>
     * footprint 从 FloorPlate 中布尔减去
     * 周边自动生成 WallType.COURTYARD
     */
    public static class CourtyardVoid {
        /** 庭院轮廓 */
        public final Polygon2D footprint;

        /** 是否完全露天 */
        public final boolean openToSky;

        /** 相邻 zone */
        public final List<String> adjacentZones;

        public CourtyardVoid(
                Polygon2D footprint,
                boolean openToSky,
                List<String> adjacentZones
        ) {
            this.footprint = footprint;
            this.openToSky = openToSky;
            this.adjacentZones = adjacentZones != null ? List.copyOf(adjacentZones) : List.of();
        }
    }

    /**
     * AxisConstraint（对齐约束）
     * <p>
     * 非几何但强影响
     * <p>
     * 来自 axes，不直接生成结构，而是：
     * - 调整墙的直线性
     * - zone 的偏移容忍度
     * - 对称策略
     */
    public static class AxisConstraint {
        public final String id;

        /** 轴线（XZ） */
        public final Line2D axis;

        /** 轴线等级 */
        public final AxisRole role;

        /** 影响的 zones */
        public final List<String> zones;

        public AxisConstraint(
                String id,
                Line2D axis,
                AxisRole role,
                List<String> zones
        ) {
            this.id = id;
            this.axis = axis;
            this.role = role != null ? role : AxisRole.SECONDARY;
            this.zones = zones != null ? List.copyOf(zones) : List.of();
        }
    }
}
