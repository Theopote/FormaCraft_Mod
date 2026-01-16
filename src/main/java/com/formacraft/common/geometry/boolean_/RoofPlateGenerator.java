package com.formacraft.common.geometry.boolean_;

import com.formacraft.common.geometry.Polygon2D;
import com.formacraft.common.llm.dto.StructuralSkeleton;
import com.formacraft.common.llm.dto.structural.RoofType;

import java.util.ArrayList;
import java.util.List;

/**
 * RoofPlateGenerator（屋顶板生成器）
 * <p>
 * 核心职责：从 FloorPlate 和 CourtyardVoid 的 Boolean 结果生成 RoofPlate
 * <p>
 * 核心规则：
 * - RoofPlate = 对 FloorPlate 的"有效实心区域"做封顶
 * - roofFootprints = EffectiveFootprint.outerPolygons（Boolean 之后的结果）
 * - 不要包含 hole
 * - roofFootprints 可以是多个 polygon（多体量）
 */
public final class RoofPlateGenerator {

    private RoofPlateGenerator() {}

    private static final double DEFAULT_ROOF_THICKNESS = 0.5;

    /**
     * 生成 RoofPlate
     * <p>
     * 流程：
     * 1. 执行 Boolean 运算（FloorPlate.footprint - CourtyardVoid.footprint(s)）
     * 2. 提取外边界 polygon（不包含 hole）
     * 3. 计算屋顶基准高度（通常 = max(wall.topY)）
     * 4. 创建 RoofPlate
     * 
     * @param floorPlate FloorPlate
     * @param courtyards CourtyardVoid 列表
     * @param wallHeight 墙高（用于计算 baseY）
     * @param roofThickness 屋顶厚度（可选，默认 0.5）
     * @return RoofPlate，如果没有有效区域则返回 null
     */
    public static StructuralSkeleton.RoofPlate generateRoofPlate(
            StructuralSkeleton.FloorPlate floorPlate,
            List<StructuralSkeleton.CourtyardVoid> courtyards,
            double wallHeight,
            Double roofThickness
    ) {
        if (floorPlate == null || floorPlate.footprint == null) {
            return null;
        }

        // Step 1: 执行 Boolean 运算（获取有效 footprint）
        List<Polygon2D> holeFootprints = new ArrayList<>();
        if (courtyards != null) {
            for (StructuralSkeleton.CourtyardVoid courtyard : courtyards) {
                // 只处理 openToSky 的 courtyard（需要屋顶绕开）
                if (courtyard != null && courtyard.openToSky && courtyard.footprint != null) {
                    holeFootprints.add(courtyard.footprint);
                }
            }
        }

        PolygonBooleanResult booleanResult = PolygonBoolean.subtract(floorPlate.footprint, holeFootprints);

        // Step 2: 提取外边界 polygon（不包含 hole）
        List<Polygon2D> roofFootprints = booleanResult.getOuterBoundaries();
        if (roofFootprints.isEmpty()) {
            // 没有有效区域，返回 null
            return null;
        }

        // Step 3: 计算屋顶基准高度（通常 = floorPlate.baseY + wallHeight）
        double baseY = floorPlate.baseY + wallHeight;

        // Step 4: 确定屋顶厚度
        double thickness = roofThickness != null ? roofThickness : DEFAULT_ROOF_THICKNESS;

        // Step 5: 创建 RoofPlate
        return new StructuralSkeleton.RoofPlate(
                roofFootprints,
                baseY,
                thickness,
                RoofType.FLAT  // v1 只支持平屋顶
        );
    }

    /**
     * 生成 RoofPlate（使用默认厚度）
     */
    public static StructuralSkeleton.RoofPlate generateRoofPlate(
            StructuralSkeleton.FloorPlate floorPlate,
            List<StructuralSkeleton.CourtyardVoid> courtyards,
            double wallHeight
    ) {
        return generateRoofPlate(floorPlate, courtyards, wallHeight, null);
    }
}
