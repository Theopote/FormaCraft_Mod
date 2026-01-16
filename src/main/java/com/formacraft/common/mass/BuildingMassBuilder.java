package com.formacraft.common.mass;

import net.minecraft.util.math.BlockPos;

import java.util.Set;

/**
 * BuildingMassBuilder（建筑体量构建器）
 * <p>
 * 提供便捷的方法来创建 BuildingMass
 * <p>
 * 这是 BuildingMass MVP 的工具类，用于简化体量的创建
 */
public final class BuildingMassBuilder {

    private BuildingMassBuilder() {}

    /**
     * 创建一个简单的矩形体量
     *
     * @param id 体量 ID
     * @param minX 最小 X 坐标
     * @param maxX 最大 X 坐标
     * @param minZ 最小 Z 坐标
     * @param maxZ 最大 Z 坐标
     * @param baseY 底部 Y 坐标
     * @param topY 顶部 Y 坐标
     * @param type 体量类型
     * @param role 语义角色
     * @return BuildingMass
     */
    public static BuildingMass createRectangularMass(
            String id,
            int minX, int maxX,
            int minZ, int maxZ,
            int baseY, int topY,
            MassType type,
            MassRole role
    ) {
        AreaMask footprint = new RectMask(minX, maxX, minZ, maxZ);
        HeightRange height = new HeightRange(baseY, topY);

        return new BuildingMass(
                id,
                footprint,
                height,
                type != null ? type : MassType.SOLID,
                MassOperation.ADD,
                role != null ? role : MassRole.PRIMARY
        );
    }

    /**
     * 创建一个基于离散方块位置的体量
     *
     * @param id 体量 ID
     * @param allowedXZ 允许的 XZ 位置集合（离散的方块位置）
     * @param baseY 底部 Y 坐标
     * @param topY 顶部 Y 坐标
     * @param type 体量类型
     * @param role 语义角色
     * @return BuildingMass
     */
    public static BuildingMass createPlanBoundedMass(
            String id,
            Set<BlockPos> allowedXZ,
            int baseY, int topY,
            MassType type,
            MassRole role
    ) {
        AreaMask footprint = new PlanBoundedMask(allowedXZ);
        HeightRange height = new HeightRange(baseY, topY);

        return new BuildingMass(
                id,
                footprint,
                height,
                type != null ? type : MassType.SOLID,
                MassOperation.ADD,
                role != null ? role : MassRole.PRIMARY
        );
    }

    /**
     * 创建一个中空体量（用于天井、中庭）
     *
     * @param id 体量 ID
     * @param minX 最小 X 坐标
     * @param maxX 最大 X 坐标
     * @param minZ 最小 Z 坐标
     * @param maxZ 最大 Z 坐标
     * @param baseY 底部 Y 坐标
     * @param topY 顶部 Y 坐标
     * @return BuildingMass（operation = SUBTRACT）
     */
    public static BuildingMass createHollowMass(
            String id,
            int minX, int maxX,
            int minZ, int maxZ,
            int baseY, int topY
    ) {
        AreaMask footprint = new RectMask(minX, maxX, minZ, maxZ);
        HeightRange height = new HeightRange(baseY, topY);

        return new BuildingMass(
                id,
                footprint,
                height,
                MassType.HOLLOW,
                MassOperation.SUBTRACT, // 相减
                MassRole.SECONDARY
        );
    }

    /**
     * 创建一个悬挑体量（平板）
     *
     * @param id 体量 ID
     * @param minX 最小 X 坐标
     * @param maxX 最大 X 坐标
     * @param minZ 最小 Z 坐标
     * @param maxZ 最大 Z 坐标
     * @param y 高度 Y 坐标（薄板）
     * @return BuildingMass（type = SLAB, role = CANTILEVER）
     */
    public static BuildingMass createCantileverSlab(
            String id,
            int minX, int maxX,
            int minZ, int maxZ,
            int y
    ) {
        AreaMask footprint = new RectMask(minX, maxX, minZ, maxZ);
        HeightRange height = new HeightRange(y, y); // 薄板（只有一层）

        return new BuildingMass(
                id,
                footprint,
                height,
                MassType.SLAB,
                MassOperation.ADD,
                MassRole.CANTILEVER
        );
    }
}
