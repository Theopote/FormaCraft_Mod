package com.formacraft.common.mass;

/**
 * AreaMask（区域掩码）
 * <p>
 * 🎯 核心定位（架构校准 2026-01-14）：
 * AreaMask 不是几何，不是多边形，而是"是否允许"。
 * <p>
 * 这是防止走偏成建模的关键设计：
 * - ✅ 离散的（基于方块位置）
 * - ✅ 贴合 Minecraft 的方块世界
 * - ✅ 没有连续几何运算
 * - ❌ 没有多边形运算
 * <p>
 * 核心思想：
 * BuildingMass 不是几何体，不是模型，不是 mesh。
 * BuildingMass 是"在某个空间域内，允许方块生成的一段体量规则集合"。
 * <p>
 * 实现：
 * - {@link RectMask} - 矩形掩码
 * - {@link PlanBoundedMask} - 基于 Plan Domain 的离散方块位置掩码
 */
public interface AreaMask {
    /**
     * 在 XZ 平面上，这个位置是否属于体量范围
     * <p>
     * 这是离散的判断，基于方块位置
     *
     * @param x X 坐标
     * @param z Z 坐标
     * @return 是否属于体量范围
     */
    boolean contains(int x, int z);
}
