package com.formacraft.common.mass;

import com.formacraft.common.llm.dto.StructuralSkeleton;

import java.util.List;

/**
 * MassToStructuralDeriver（体量组合到结构骨架派生器）
 * <p>
 * 核心职责：从 MassAssembly（体量组合）派生 StructuralSkeleton（候选结构）
 * <p>
 * 正确的生成流程：
 * ```
 * MassAssembly (体量组合)
 *   ↓
 * MassToStructuralDeriver
 *   ↓
 * StructuralSkeleton (从体量组合派生)
 * ```
 * <p>
 * 派生规则：
 * 1. 每个体量的每一层 → FloorPlate（候选）
 * 2. 每个体量的边界 + 关系 → WallSegment（候选）
 * 3. 每个体量的顶部 → RoofPlate（候选）
 * <p>
 * 实现状态：
 * - Phase 2: 简单的单个体量派生（已完成）
 * - Phase 3: 体量关系处理（待实现）
 * - Phase 4: 复杂关系和 Boolean 运算（待实现）
 */
public final class MassToStructuralDeriver {

    private MassToStructuralDeriver() {}

    /**
     * 从体量组合派生 StructuralSkeleton
     * <p>
     * 这是架构校准后的正确流程：
     * - StructuralSkeleton 不再是 Plan 的必然结果
     * - StructuralSkeleton 是从体量组合派生的候选结构
     * <p>
     * 当前实现（Phase 2）：
     * - 支持简单的单个体量（BLOCK）
     * - 从体量派生 FloorPlate 和 WallSegment
     * - 暂不支持体量关系和复杂组合
     *
     * @param massAssembly 体量组合
     * @return StructuralSkeleton（候选结构）
     */
    public static StructuralSkeleton deriveFromMassAssembly(MassAssembly massAssembly) {
        if (massAssembly == null) {
            throw new IllegalArgumentException("MassAssembly cannot be null");
        }

        if (massAssembly.masses == null || massAssembly.masses.isEmpty()) {
            throw new IllegalArgumentException("MassAssembly must contain at least one mass");
        }

        // Phase 2: 从体量组合派生结构
        // 1. 派生 FloorPlate（每个体量的每一层）
        List<StructuralSkeleton.FloorPlate> floorPlates = MassToFloorPlateDeriver.deriveAllFloorPlates(massAssembly);
        
        // v1 简化：使用第一个 FloorPlate 作为主要的 FloorPlate
        // 未来：可能需要合并多个 FloorPlate 或选择主要的
        StructuralSkeleton.FloorPlate mainFloorPlate = floorPlates.isEmpty() 
                ? null 
                : floorPlates.get(0);

        // 2. 派生 WallSegment（每个体量的边界）
        List<StructuralSkeleton.WallSegment> wallSegments = MassToWallSegmentDeriver.deriveAllWallSegments(massAssembly);

        // 3. 派生 RoofPlate（每个体量的顶部）
        // TODO: Phase 2.5 - 实现从体量派生 RoofPlate
        StructuralSkeleton.RoofPlate roofPlate = null; // 暂不生成

        // 4. CourtyardVoid（体量组合暂不生成庭院）
        List<StructuralSkeleton.CourtyardVoid> courtyards = List.of();

        // 5. AxisConstraint（从 Domain 提取）
        List<StructuralSkeleton.AxisConstraint> axes = List.of(); // TODO: 从 domain.axes() 提取

        return new StructuralSkeleton(
                mainFloorPlate,
                wallSegments,
                courtyards,
                roofPlate,
                axes
        );
    }
}
