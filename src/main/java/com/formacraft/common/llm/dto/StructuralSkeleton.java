package com.formacraft.common.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import net.minecraft.util.math.BlockPos;

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
 * v1 只需要 5 种 Structural Element：
 * - FLOOR_PLATE - 地面板
 * - WALL_SEGMENT - 墙段
 * - COURTYARD_VOID - 庭院空洞
 * - ROOF_PLATE - 屋顶板
 * - VERTICAL_CORE - 垂直核心（可选，v1 可忽略）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StructuralSkeleton(
        @JsonProperty("schema") String schema,
        @JsonProperty("floor_plate") FloorPlate floorPlate,
        @JsonProperty("wall_segments") List<WallSegment> wallSegments,
        @JsonProperty("courtyard_voids") List<CourtyardVoid> courtyardVoid,
        @JsonProperty("roof_plate") RoofPlate roofPlate,
        @JsonProperty("alignment_constraints") List<AlignmentConstraint> alignmentConstraints
) {
    /**
     * FloorPlate：地面板
     * <p>
     * floor plate 是唯一必定存在的元素。
     * <p>
     * 仅定义：
     * - XZ 范围（polygon）
     * - 基准高度（通常 y=0 或 terrain 对齐）
     * <p>
     * ⚠️ 注意：floor plate ≠ 房间，它只是承载墙和体量的"地基"
     */
    public record FloorPlate(
            @JsonProperty("polygon_xz") List<BlockPos> polygonXZ,  // XZ 平面上的点（y 忽略）
            @JsonProperty("base_y") Integer baseY
    ) {}

    /**
     * WallSegment：墙段
     * <p>
     * 核心元素，决定：
     * - 墙的位置（baseLine）
     * - 墙的类型（external/internal/courtyard）
     * - 墙的高度
     * - 法线方向（outward/inward）
     */
    public record WallSegment(
            @JsonProperty("id") String id,
            @JsonProperty("kind") String kind,
            @JsonProperty("base_line") List<BlockPos> baseLine,  // 墙基线（XZ平面）
            @JsonProperty("height") Integer height,
            @JsonProperty("normal") String normal,  // 法线方向（NORTH/SOUTH/EAST/WEST 或 OUTWARD/INWARD）
            @JsonProperty("zone_ids") List<String> zoneIds
    ) {
        public enum Kind {
            EXTERNAL,      // 外墙
            INTERNAL,      // 内墙（shared_wall）
            COURTYARD      // 庭院墙（内向立面）
        }
    }

    /**
     * CourtyardVoid：庭院空洞
     * <p>
     * 体量减法：不生成 FLOOR_PLATE，不生成 ROOF。
     * 但周围生成 courtyard_wall（inward-facing）。
     * <p>
     * 这是回字形 / 中式院落 / 修道院的根。
     */
    public record CourtyardVoid(
            @JsonProperty("id") String id,
            @JsonProperty("polygon_xz") List<BlockPos> polygonXZ,  // 空洞边界（XZ平面）
            @JsonProperty("open_to_sky") Boolean openToSky,
            @JsonProperty("adjacent_zone_ids") List<String> adjacentZoneIds
    ) {}

    /**
     * RoofPlate：屋顶板
     * <p>
     * v1 简化：统一屋顶（从 outline.offset(inward) 生成）
     * <p>
     * 后续可支持：
     * - 分区屋顶
     * - 高低错动
     * - 坡屋顶
     */
    public record RoofPlate(
            @JsonProperty("polygon_xz") List<BlockPos> polygonXZ,
            @JsonProperty("base_y") Integer baseY,
            @JsonProperty("thickness") Integer thickness
    ) {}

    /**
     * AlignmentConstraint：对齐约束
     * <p>
     * 来自 axes，不直接生成结构，而是：
     * - 调整墙的直线性
     * - zone 的偏移容忍度
     * - 对称策略
     */
    public record AlignmentConstraint(
            @JsonProperty("axis_id") String axisId,
            @JsonProperty("role") String role,
            @JsonProperty("zone_ids") List<String> zoneIds,
            @JsonProperty("preferences") AlignmentPreferences preferences
    ) {
        public enum Role {
            primary,    // 主轴线：尽量直线、尽量正交
            secondary,  // 次轴线：可偏移
            symmetry    // 对称轴
        }

        public record AlignmentPreferences(
                @JsonProperty("straightness") Double straightness,  // 0.0-1.0，直线性偏好
                @JsonProperty("orthogonal") Boolean orthogonal,      // 是否偏好正交
                @JsonProperty("symmetry") Boolean symmetry          // 是否对称
        ) {}
    }
}
