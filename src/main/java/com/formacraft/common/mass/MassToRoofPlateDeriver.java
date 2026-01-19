package com.formacraft.common.mass;

import com.formacraft.common.geometry.boolean_.RoofPlateGenerator;
import com.formacraft.common.llm.dto.StructuralSkeleton;

import java.util.List;

/**
 * MassToRoofPlateDeriver（体量到屋顶板派生器）
 * <p>
 * 核心职责：从 MassAssembly（体量组合）派生 RoofPlate（候选屋顶）
 * <p>
 * 派生规则：
 * - 每个体量的顶部 → 一个 RoofPlate footprint
 * - 屋顶高度 = 最高体量的顶部
 * - 合并多个体量的屋顶 footprints（如果重叠）
 * <p>
 * 这是 Phase 2.5 的实现：从体量派生 RoofPlate
 */
public final class MassToRoofPlateDeriver {

    private MassToRoofPlateDeriver() {}

    private static final double DEFAULT_WALL_HEIGHT = 4.0; // 默认墙高（block）
    private static final double DEFAULT_ROOF_THICKNESS = 0.5; // 默认屋顶厚度

    /**
     * 从 MassAssembly 派生 RoofPlate
     * <p>
     * 流程：
     * 1. 获取最高的 FloorPlate（从 MassToFloorPlateDeriver）
     * 2. 使用 RoofPlateGenerator 生成 RoofPlate
     * 3. 合并多个体量的屋顶（v2 扩展）
     *
     * @param massAssembly 体量组合
     * @param mainFloorPlate 主要的 FloorPlate（已从 MassToFloorPlateDeriver 派生）
     * @return RoofPlate，如果没有有效体量则返回 null
     */
    public static StructuralSkeleton.RoofPlate deriveRoofPlate(
            MassAssembly massAssembly,
            StructuralSkeleton.FloorPlate mainFloorPlate
    ) {
        if (massAssembly == null || massAssembly.masses == null || massAssembly.masses.isEmpty()) {
            return null;
        }

        if (mainFloorPlate == null) {
            return null;
        }

        // 使用 RoofPlateGenerator 生成 RoofPlate
        // v1 简化：使用主要的 FloorPlate，不考虑庭院（courtyards = empty）
        // 计算墙高（从体量高度推断）
        double wallHeight = estimateWallHeight(massAssembly);

        // 生成 RoofPlate（没有庭院，所以 courtyards = empty）
        return RoofPlateGenerator.generateRoofPlate(
                mainFloorPlate,
                List.of(), // v1 简化：体量组合暂不生成庭院
                wallHeight,
                DEFAULT_ROOF_THICKNESS
        );
    }

    /**
     * 估计墙高
     * <p>
     * 从体量的平均层高推断墙高
     * v1 简化：使用默认值或从 floorHeight 推断
     */
    private static double estimateWallHeight(MassAssembly massAssembly) {
        if (massAssembly.masses == null || massAssembly.masses.isEmpty()) {
            return DEFAULT_WALL_HEIGHT;
        }

        // 计算平均层高
        double totalFloorHeight = 0.0;
        int count = 0;
        for (MassDefinition mass : massAssembly.masses) {
            if (mass.floorHeight > 0) {
                totalFloorHeight += mass.floorHeight;
                count++;
            }
        }

        if (count > 0) {
            double avgFloorHeight = totalFloorHeight / count;
            // 墙高 ≈ 层高（简化）
            return Math.max(3.0, Math.min(5.0, avgFloorHeight));
        }

        return DEFAULT_WALL_HEIGHT;
    }

    /**
     * 从单个 MassDefinition 派生 RoofPlate（辅助方法）
     * <p>
     * v1 简化：为单个 MassDefinition 生成 RoofPlate
     * 主要用于测试和单个体量的屋顶生成
     *
     * @param mass 体量定义
     * @return RoofPlate
     */
    public static StructuralSkeleton.RoofPlate deriveRoofPlateForMass(MassDefinition mass) {
        if (mass == null) {
            return null;
        }

        // 生成一个临时的 FloorPlate
        List<StructuralSkeleton.FloorPlate> floorPlates = MassToFloorPlateDeriver.deriveFloorPlates(mass);
        if (floorPlates.isEmpty()) {
            return null;
        }

        // 使用最高的 FloorPlate（最后一层）
        StructuralSkeleton.FloorPlate topFloorPlate = floorPlates.getLast();

        // 创建临时的 MassAssembly
        MassAssembly tempAssembly = MassAssembly.empty(null).withMass(mass);

        return deriveRoofPlate(tempAssembly, topFloorPlate);
    }
}
