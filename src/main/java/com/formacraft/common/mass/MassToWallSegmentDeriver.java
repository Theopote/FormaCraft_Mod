package com.formacraft.common.mass;

import com.formacraft.common.geometry.Polyline2D;
import com.formacraft.common.geometry.Vec2;
import com.formacraft.common.geometry.Vector2;
import com.formacraft.common.llm.dto.StructuralSkeleton;
import com.formacraft.common.llm.dto.structural.HeightProfile;
import com.formacraft.common.llm.dto.structural.WallType;

import java.util.ArrayList;
import java.util.List;

/**
 * MassToWallSegmentDeriver（体量到墙段派生器）
 * <p>
 * 核心职责：从 MassDefinition 派生 WallSegment（候选墙段）
 * <p>
 * 派生规则：
 * - 每个体量的每个外边界 → 一个 WallSegment（候选）
 * - 考虑体量关系（ATTACHED 时共用面不生成墙）
 * - OVERHANG 时生成特殊的墙段
 * <p>
 * 这是 Phase 2/3 的实现：从体量派生 WallSegment
 */
public final class MassToWallSegmentDeriver {

    private MassToWallSegmentDeriver() {}

    /**
     * 从单个 MassDefinition 派生 WallSegment 列表
     * <p>
     * v1 简化：为体量的四个外边界生成墙段
     * 未来：考虑旋转、非矩形体量、体量关系
     *
     * @param mass 体量定义
     * @return WallSegment 列表
     */
    public static List<StructuralSkeleton.WallSegment> deriveWallSegments(MassDefinition mass) {
        if (mass == null || mass.bounds == null) {
            return List.of();
        }

        List<StructuralSkeleton.WallSegment> wallSegments = new ArrayList<>();

        // 提取体量的边界（XZ 投影）
        double minX = mass.bounds.minX;
        double minZ = mass.bounds.minZ;
        double maxX = mass.bounds.maxX;
        double maxZ = mass.bounds.maxZ;

        // 计算体量高度
        double height = mass.bounds.maxY - mass.bounds.minY;
        double baseY = mass.bounds.minY;

        // 为四个边界生成墙段
        // 1. 北边界（minZ）
        wallSegments.add(createWallSegment(
                mass.id + "_wall_north",
                new Vec2(minX, minZ),
                new Vec2(maxX, minZ),
                baseY,
                height,
                WallType.EXTERNAL, // v1 简化：假设都是外墙
                Vector2.from(new Vec2(0, -1)) // 法线向北（向外）
        ));

        // 2. 南边界（maxZ）
        wallSegments.add(createWallSegment(
                mass.id + "_wall_south",
                new Vec2(maxX, maxZ),
                new Vec2(minX, maxZ),
                baseY,
                height,
                WallType.EXTERNAL,
                Vector2.from(new Vec2(0, 1)) // 法线向南（向外）
        ));

        // 3. 东边界（maxX）
        wallSegments.add(createWallSegment(
                mass.id + "_wall_east",
                new Vec2(maxX, minZ),
                new Vec2(maxX, maxZ),
                baseY,
                height,
                WallType.EXTERNAL,
                Vector2.from(new Vec2(1, 0)) // 法线向东（向外）
        ));

        // 4. 西边界（minX）
        wallSegments.add(createWallSegment(
                mass.id + "_wall_west",
                new Vec2(minX, maxZ),
                new Vec2(minX, minZ),
                baseY,
                height,
                WallType.EXTERNAL,
                Vector2.from(new Vec2(-1, 0)) // 法线向西（向外）
        ));

        return wallSegments;
    }

    /**
     * 创建墙段
     */
    private static StructuralSkeleton.WallSegment createWallSegment(
            String id,
            Vec2 start,
            Vec2 end,
            double baseY,
            double height,
            WallType type,
            Vector2 normal
    ) {
        // 创建基线
        Polyline2D baseline = Polyline2D.line(start, end);

        // 创建高度配置
        HeightProfile heightProfile = HeightProfile.fixed(baseY, height);

        return new StructuralSkeleton.WallSegment(
                id,
                type,
                baseline,
                1.0, // 默认墙厚
                heightProfile,
                normal,
                List.of() // v1 简化：不关联 zone
        );
    }

    /**
     * 从 MassAssembly 派生所有 WallSegment
     * <p>
     * 遍历所有体量，为每个体量生成墙段
     * <p>
     * v1 简化：不考虑体量关系（未来需要处理 ATTACHED 时共用面不生成墙）
     *
     * @param massAssembly 体量组合
     * @return 所有 WallSegment 列表
     */
    public static List<StructuralSkeleton.WallSegment> deriveAllWallSegments(MassAssembly massAssembly) {
        if (massAssembly == null || massAssembly.masses == null) {
            return List.of();
        }

        List<StructuralSkeleton.WallSegment> allWallSegments = new ArrayList<>();

        for (MassDefinition mass : massAssembly.masses) {
            List<StructuralSkeleton.WallSegment> wallSegments = deriveWallSegments(mass);
            allWallSegments.addAll(wallSegments);
        }

        // Phase 3: 根据体量关系调整墙段
        return MassRelationshipProcessor.processRelationships(allWallSegments, massAssembly);
    }
}
