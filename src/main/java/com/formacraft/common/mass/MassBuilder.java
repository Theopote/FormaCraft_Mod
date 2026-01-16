package com.formacraft.common.mass;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3i;

/**
 * MassBuilder（体量构建器）
 * <p>
 * 提供便捷的方法来创建 MassDefinition
 * <p>
 * 这是 Building Mass Assembly 的工具类，用于简化体量的创建
 */
public final class MassBuilder {

    private MassBuilder() {}

    /**
     * 创建一个简单的 BLOCK 体量
     * <p>
     * v1 简化：创建基础的方盒子体量
     *
     * @param id 体量 ID
     * @param x 在 Domain 内的 X 坐标（相对于 Domain 原点）
     * @param y 在 Domain 内的 Y 坐标（相对于 Domain 原点）
     * @param z 在 Domain 内的 Z 坐标（相对于 Domain 原点）
     * @param width 宽度（X 方向）
     * @param height 高度（Y 方向，总高度）
     * @param depth 深度（Z 方向）
     * @param floorCount 层数
     * @param floorHeight 层高
     * @return MassDefinition
     */
    public static MassDefinition createBlock(
            String id,
            int x, int y, int z,
            int width, int height, int depth,
            int floorCount,
            double floorHeight
    ) {
        // 创建边界框（相对于 Domain 原点）
        Box bounds = new Box(
                x, y, z,
                x + width, y + height, z + depth
        );

        return new MassDefinition(
                id,
                MassPrototype.BLOCK,
                bounds,
                new Vec3i(x, y, z), // offset = 位置
                MassDefinition.Rotation.NONE,
                floorCount,
                floorHeight
        );
    }

    /**
     * 创建一个简单的 SLAB 体量（平板）
     * <p>
     * 用于悬挑、平台等
     *
     * @param id 体量 ID
     * @param x 在 Domain 内的 X 坐标
     * @param y 在 Domain 内的 Y 坐标
     * @param z 在 Domain 内的 Z 坐标
     * @param width 宽度
     * @param thickness 厚度（通常较小）
     * @param depth 深度
     * @return MassDefinition
     */
    public static MassDefinition createSlab(
            String id,
            int x, int y, int z,
            int width, int thickness, int depth
    ) {
        Box bounds = new Box(
                x, y, z,
                x + width, y + thickness, z + depth
        );

        return new MassDefinition(
                id,
                MassPrototype.SLAB,
                bounds,
                new Vec3i(x, y, z),
                MassDefinition.Rotation.NONE,
                1, // SLAB 通常只有一层
                1.0 // 层高 = 厚度
        );
    }

    /**
     * 创建一个简单的 TOWER 体量（塔）
     * <p>
     * 高度显著大于宽度/深度
     *
     * @param id 体量 ID
     * @param x 在 Domain 内的 X 坐标
     * @param y 在 Domain 内的 Y 坐标
     * @param z 在 Domain 内的 Z 坐标
     * @param width 宽度（通常较小）
     * @param height 高度（通常较大）
     * @param depth 深度（通常较小）
     * @param floorCount 层数
     * @param floorHeight 层高
     * @return MassDefinition
     */
    public static MassDefinition createTower(
            String id,
            int x, int y, int z,
            int width, int height, int depth,
            int floorCount,
            double floorHeight
    ) {
        Box bounds = new Box(
                x, y, z,
                x + width, y + height, z + depth
        );

        return new MassDefinition(
                id,
                MassPrototype.TOWER,
                bounds,
                new Vec3i(x, y, z),
                MassDefinition.Rotation.NONE,
                floorCount,
                floorHeight
        );
    }
}
