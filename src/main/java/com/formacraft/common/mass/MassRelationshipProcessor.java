package com.formacraft.common.mass;

import com.formacraft.common.geometry.Vec2;
import com.formacraft.common.llm.dto.StructuralSkeleton;

import java.util.*;

/**
 * MassRelationshipProcessor（体量关系处理器）
 * <p>
 * 核心职责：根据体量关系调整 WallSegment 的生成
 * <p>
 * 处理规则：
 * - ATTACHED：移除共用面的墙段
 * - INTERSECT：处理重叠区域的墙段
 * - OVERHANG：生成特殊的悬挑墙段
 * - OFFSET：处理错动位置的墙段
 * <p>
 * 这是 Phase 3 的实现：体量关系处理
 */
public final class MassRelationshipProcessor {

    private MassRelationshipProcessor() {}

    /**
     * 根据体量关系调整 WallSegment 列表
     * <p>
     * 这是 Phase 3 的核心功能：
     * - 移除共用面的墙段（ATTACHED）
     * - 处理重叠区域（INTERSECT）
     * - 处理悬挑（OVERHANG）
     * - 处理错动（OFFSET）
     *
     * @param wallSegments 初始墙段列表（从体量边界生成）
     * @param massAssembly 体量组合（包含关系信息）
     * @return 调整后的墙段列表
     */
    public static List<StructuralSkeleton.WallSegment> processRelationships(
            List<StructuralSkeleton.WallSegment> wallSegments,
            MassAssembly massAssembly
    ) {
        if (wallSegments == null || wallSegments.isEmpty()) {
            return List.of();
        }

        if (massAssembly == null || massAssembly.relationships == null || massAssembly.relationships.isEmpty()) {
            // 没有关系，直接返回原始墙段
            return new ArrayList<>(wallSegments);
        }

        // 创建体量 ID 到 MassDefinition 的映射
        Map<String, MassDefinition> massMap = new HashMap<>();
        if (massAssembly.masses != null) {
            for (MassDefinition mass : massAssembly.masses) {
                massMap.put(mass.id, mass);
            }
        }

        // 复制墙段列表（用于修改）
        List<StructuralSkeleton.WallSegment> processedWalls = new ArrayList<>(wallSegments);

        // 处理每个关系
        for (MassRelationship relationship : massAssembly.relationships) {
            switch (relationship.type) {
                case ATTACHED -> processAttached(processedWalls, relationship, massMap);
                case OFFSET -> processOffset(processedWalls, relationship, massMap);
                case INTERSECT -> processIntersect(processedWalls, relationship, massMap);
                case OVERHANG -> processOverhang(processedWalls, relationship, massMap);
            }
        }

        return processedWalls;
    }

    /**
     * 处理 ATTACHED 关系（附着）
     * <p>
     * 规则：移除两个体量共用面的墙段
     * <p>
     * v1 简化：检测墙段是否在共用面上，如果是则移除
     */
    private static void processAttached(
            List<StructuralSkeleton.WallSegment> walls,
            MassRelationship relationship,
            Map<String, MassDefinition> massMap
    ) {
        MassDefinition massA = massMap.get(relationship.massA);
        MassDefinition massB = massMap.get(relationship.massB);

        if (massA == null || massB == null) {
            return;
        }

        // 找到两个体量之间的共用面
        // v1 简化：检测墙段是否在两个体量的边界上
        walls.removeIf(wall -> {
            // 检查墙段是否属于 massA 或 massB
            boolean belongsToA = wall.id.startsWith(relationship.massA);
            boolean belongsToB = wall.id.startsWith(relationship.massB);

            if (!belongsToA && !belongsToB) {
                return false; // 不属于这两个体量，保留
            }

            // v1 简化：如果墙段在两个体量的边界上，则移除
            // 未来：需要更精确的几何检测
            return isOnSharedFace(wall, massA, massB);
        });
    }

    /**
     * 处理 OFFSET 关系（错动）
     * <p>
     * 规则：保持墙段，但可能需要调整高度或位置
     * <p>
     * v1 简化：暂不调整，保持原样
     */
    private static void processOffset(
            List<StructuralSkeleton.WallSegment> walls,
            MassRelationship relationship,
            Map<String, MassDefinition> massMap
    ) {
        // v1 简化：OFFSET 关系暂不处理
        // 未来：可能需要调整墙段的高度或位置
    }

    /**
     * 处理 INTERSECT 关系（穿插）
     * <p>
     * 规则：处理重叠区域的墙段
     * <p>
     * v1 简化：暂不处理
     */
    private static void processIntersect(
            List<StructuralSkeleton.WallSegment> walls,
            MassRelationship relationship,
            Map<String, MassDefinition> massMap
    ) {
        // v1 简化：INTERSECT 关系暂不处理
        // 未来：需要 Boolean 运算处理重叠区域
    }

    /**
     * 处理 OVERHANG 关系（悬挑）
     * <p>
     * 规则：为悬挑体量生成特殊的墙段
     * <p>
     * v1 简化：保持悬挑体量的墙段，但标记为特殊类型
     */
    private static void processOverhang(
            List<StructuralSkeleton.WallSegment> walls,
            MassRelationship relationship,
            Map<String, MassDefinition> massMap
    ) {
        MassDefinition overhangMass = massMap.get(relationship.massB);
        if (overhangMass == null) {
            return;
        }

        // v1 简化：悬挑体量的墙段保持原样
        // 未来：可能需要生成特殊的悬挑墙段（例如：底部不生成墙）
    }

    /**
     * 检查墙段是否在两个体量的共用面上
     * <p>
     * v1 简化：基于边界框的简单检测
     * 未来：需要更精确的几何检测
     */
    private static boolean isOnSharedFace(
            StructuralSkeleton.WallSegment wall,
            MassDefinition massA,
            MassDefinition massB
    ) {
        if (wall.baseline == null || massA.bounds == null || massB.bounds == null) {
            return false;
        }

        // v1 简化：检查墙段的基线是否在两个体量的边界上
        // 这里使用简单的边界框检测
        // 未来：需要更精确的几何计算

        // 获取墙段基线的起点和终点
        List<Vec2> points = wall.baseline.getPoints();
        if (points.isEmpty()) {
            return false;
        }

        // 检查墙段是否在 massA 和 massB 的边界上
        // v1 简化：检查墙段是否在某个体量的边界上
        // 未来：需要检测是否在两个体量的共用面上

        return false; // v1 简化：暂不检测，保留所有墙段
    }
}
