package com.formacraft.common.mass;

import net.minecraft.util.math.Direction;

/**
 * MassFilledChecker（体量占用检查器）
 * <p>
 * 🎯 核心定位（架构校准 2026-01-14）：
 * 统一的体量占用判断函数
 * <p>
 * 这是所有 Skeleton / Socket 派生的前提：
 * 所有派生都只依赖 isFilled 判断函数
 * <p>
 * ⚠️ 关键：
 * - 基于 BuildingMass 组合规则
 * - 基于离散方块位置判断
 * - 不涉及连续几何
 */
public final class MassFilledChecker {
    
    private MassFilledChecker() {}

    /**
     * 判断指定位置是否被体量占用
     * <p>
     * 这是所有 Skeleton / Socket 派生的基础判断
     *
     * @param composition 体量组合
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 是否被占用
     */
    public static boolean isFilled(BuildingMassComposition composition, int x, int y, int z) {
        if (composition == null) {
            return false;
        }
        return composition.allowsBlockAt(x, y, z);
    }

    /**
     * 判断指定方向的相邻位置是否被占用
     *
     * @param composition 体量组合
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @param dir 方向
     * @return 相邻位置是否被占用
     */
    public static boolean isFilledInDirection(
            BuildingMassComposition composition,
            int x, int y, int z,
            Direction dir
    ) {
        return isFilled(composition, 
                x + dir.getOffsetX(),
                y + dir.getOffsetY(),
                z + dir.getOffsetZ()
        );
    }

    /**
     * 判断指定位置是否是外暴露边界
     * <p>
     * 判定条件：
     * - isFilled(x,y,z) == true
     * - isFilled(x+dir.x, y+dir.y, z+dir.z) == false
     * <p>
     * 这个"面"就是一个 Exterior Face
     *
     * @param composition 体量组合
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @param dir 方向
     * @return 是否是外暴露边界
     */
    public static boolean isExteriorBoundary(
            BuildingMassComposition composition,
            int x, int y, int z,
            Direction dir
    ) {
        return isFilled(composition, x, y, z) 
            && !isFilledInDirection(composition, x, y, z, dir);
    }

    /**
     * 判断指定位置是否是内接触边界
     * <p>
     * 判定条件：
     * - isFilled(x,y,z) == true
     * - isFilled(x+dir.x, y+dir.y, z+dir.z) == true
     * - 但来自不同的 BuildingMass 或高度区间不同
     * <p>
     * ⚠️ 注意：v1 简化：只检查是否都被占用，暂不区分是否来自不同 Mass
     *
     * @param composition 体量组合
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @param dir 方向
     * @return 是否是内接触边界
     */
    public static boolean isInterfaceBoundary(
            BuildingMassComposition composition,
            int x, int y, int z,
            Direction dir
    ) {
        boolean filledHere = isFilled(composition, x, y, z);
        boolean filledNeighbor = isFilledInDirection(composition, x, y, z, dir);
        
        // v1 简化：如果两个位置都被占用，就是内接触边界
        // 未来：需要检查是否来自不同的 BuildingMass
        return filledHere && filledNeighbor;
    }

    /**
     * 判断指定位置是否是顶部边界
     * <p>
     * 判定条件：
     * - isFilled(x,y,z) == true
     * - isFilled(x, y+1, z) == false
     * <p>
     * 这是一个 Top Face
     *
     * @param composition 体量组合
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 是否是顶部边界
     */
    public static boolean isTopBoundary(
            BuildingMassComposition composition,
            int x, int y, int z
    ) {
        return isFilled(composition, x, y, z)
            && !isFilledInDirection(composition, x, y, z, Direction.UP);
    }
}
