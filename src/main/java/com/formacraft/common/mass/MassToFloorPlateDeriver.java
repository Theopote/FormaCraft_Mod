package com.formacraft.common.mass;

import com.formacraft.common.geometry.Polygon2D;
import com.formacraft.common.geometry.Vec2;
import com.formacraft.common.llm.dto.StructuralSkeleton;
import com.formacraft.common.llm.dto.structural.GroundingMode;

import java.util.ArrayList;
import java.util.List;

/**
 * MassToFloorPlateDeriver（体量到地面板派生器）
 * <p>
 * 核心职责：从 MassDefinition 派生 FloorPlate（候选地面板）
 * <p>
 * 派生规则：
 * - 每个体量的每一层 → 一个 FloorPlate（候选）
 * - FloorPlate 的 footprint = 体量在该层的 XZ 投影
 * - FloorPlate 的 baseY = 体量底部 + 层高 × 层数
 * <p>
 * 这是 Phase 2 的实现：从单个体量派生 FloorPlate
 */
public final class MassToFloorPlateDeriver {

    private MassToFloorPlateDeriver() {}

    /**
     * 从单个 MassDefinition 派生 FloorPlate 列表
     * <p>
     * 每个体量的每一层都会生成一个 FloorPlate
     *
     * @param mass 体量定义
     * @return FloorPlate 列表（每个体量的每一层一个）
     */
    public static List<StructuralSkeleton.FloorPlate> deriveFloorPlates(MassDefinition mass) {
        if (mass == null || mass.bounds == null) {
            return List.of();
        }

        List<StructuralSkeleton.FloorPlate> floorPlates = new ArrayList<>();

        // 计算体量的 XZ 投影（footprint）
        Polygon2D footprint = extractFootprint(mass);

        // 为每一层生成一个 FloorPlate
        for (int floor = 0; floor < mass.floorCount; floor++) {
            // 计算该层的 baseY
            double baseY = mass.bounds.minY + (floor * mass.floorHeight);

            // 创建 FloorPlate
            StructuralSkeleton.FloorPlate floorPlate = new StructuralSkeleton.FloorPlate(
                    footprint,
                    baseY,
                    1.0, // 默认厚度
                    GroundingMode.FLAT // v1 简化：默认平铺
            );

            floorPlates.add(floorPlate);
        }

        return floorPlates;
    }

    /**
     * 从 MassDefinition 提取 footprint（XZ 投影）
     * <p>
     * v1 简化：从 bounds 提取矩形 footprint
     * 未来：支持旋转、非矩形体量
     */
    private static Polygon2D extractFootprint(MassDefinition mass) {
        if (mass.bounds == null) {
            // 默认矩形
            return Polygon2D.rectangle(
                    new Vec2(-5, -5),
                    new Vec2(5, 5)
            );
        }

        // 从 bounds 提取 XZ 投影
        double minX = mass.bounds.minX;
        double minZ = mass.bounds.minZ;
        double maxX = mass.bounds.maxX;
        double maxZ = mass.bounds.maxZ;

        // 创建矩形 footprint
        return Polygon2D.rectangle(
                new Vec2(minX, minZ),
                new Vec2(maxX, maxZ)
        );
    }

    /**
     * 从 MassAssembly 派生所有 FloorPlate
     * <p>
     * 遍历所有体量，为每个体量的每一层生成 FloorPlate
     *
     * @param massAssembly 体量组合
     * @return 所有 FloorPlate 列表
     */
    public static List<StructuralSkeleton.FloorPlate> deriveAllFloorPlates(MassAssembly massAssembly) {
        if (massAssembly == null || massAssembly.masses == null) {
            return List.of();
        }

        List<StructuralSkeleton.FloorPlate> allFloorPlates = new ArrayList<>();

        for (MassDefinition mass : massAssembly.masses) {
            List<StructuralSkeleton.FloorPlate> floorPlates = deriveFloorPlates(mass);
            allFloorPlates.addAll(floorPlates);
        }

        return allFloorPlates;
    }
}
