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

    /**
     * 使用 V3 生成器生成屋顶（支持中式屋顶：歇山/庑殿/多脊系统）
     * <p>
     * 当指定了 HIP 或 XIESHAN 形式时，使用 RoofRidgeGeneratorV3 生成脊系统
     *
     * @param floorPlate FloorPlate
     * @param courtyards CourtyardVoid 列表
     * @param wallHeight 墙高
     * @param form 屋顶形式（如果为 HIP 或 XIESHAN，则生成 V3 脊系统）
     * @param structural StructuralSkeleton（用于获取 Axis 信息）
     * @return RoofPlate（如果 form 是 HIP/XIESHAN，则包含 V3 脊系统）
     */
    public static StructuralSkeleton.RoofPlate generateRoofPlateV3(
            StructuralSkeleton.FloorPlate floorPlate,
            List<StructuralSkeleton.CourtyardVoid> courtyards,
            double wallHeight,
            com.formacraft.common.llm.dto.structural.RoofForm form,
            StructuralSkeleton structural // 用于获取 Axis 信息
    ) {
        // 先生成基础 RoofPlate
        StructuralSkeleton.RoofPlate baseRoof = generateRoofPlate(floorPlate, courtyards, wallHeight, null);
        
        if (baseRoof == null || structural == null) {
            return baseRoof;
        }
        
        // 如果指定的 form 是 HIP 或 XIESHAN，使用 V3 生成器生成脊系统
        if (form != null && 
            (form == com.formacraft.common.llm.dto.structural.RoofForm.HIP || 
             form == com.formacraft.common.llm.dto.structural.RoofForm.XIESHAN)) {
            try {
                // 使用 V2 构造函数创建包含 form 的临时 RoofPlate
                StructuralSkeleton.RoofPlate tempRoof = new StructuralSkeleton.RoofPlate(
                        baseRoof.roofFootprints,
                        baseRoof.baseY,
                        baseRoof.thickness,
                        baseRoof.type,
                        form,
                        List.of(), // 暂时为空，待生成
                        List.of()  // 暂时为空，待生成
                );
                
                // 使用 RoofRidgeGeneratorV3 生成脊系统
                var ridges = com.formacraft.common.geometry.boolean_.RoofRidgeGeneratorV3.generateRidgeSystem(
                        tempRoof, structural
                );
                
                // 使用 V2 构造函数创建包含 ridges 的最终 RoofPlate
                return new StructuralSkeleton.RoofPlate(
                        baseRoof.roofFootprints,
                        baseRoof.baseY,
                        baseRoof.thickness,
                        baseRoof.type,
                        form,
                        ridges,
                        List.of() // v3 简化：slopes 暂时为空
                );
            } catch (Exception e) {
                // v1 简化：如果 V3 生成失败，返回基础 RoofPlate
                com.formacraft.FormacraftMod.LOGGER.warn("Failed to generate V3 roof ridges, using base roof", e);
                return baseRoof;
            }
        }
        
        return baseRoof;
    }
}
