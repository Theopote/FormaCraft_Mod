package com.formacraft.common.mass;

import net.minecraft.util.math.BlockPos;

import java.util.Set;

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

/**
 * RectMask（矩形掩码）
 * <p>
 * v1 最小实现：简单的矩形区域
 */
class RectMask implements AreaMask {
    private final int minX;
    private final int maxX;
    private final int minZ;
    private final int maxZ;

    public RectMask(int minX, int maxX, int minZ, int maxZ) {
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    @Override
    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}

/**
 * PlanBoundedMask（基于 Plan Domain 的掩码）
 * <p>
 * 使用离散的方块位置集合
 * <p>
 * v1 最小实现：使用 BlockPos 集合
 */
class PlanBoundedMask implements AreaMask {
    private final Set<BlockPos> allowedXZ;

    public PlanBoundedMask(Set<BlockPos> allowedXZ) {
        this.allowedXZ = allowedXZ != null ? Set.copyOf(allowedXZ) : Set.of();
    }

    @Override
    public boolean contains(int x, int z) {
        // 检查是否有相同 XZ 的位置（忽略 Y）
        return allowedXZ.stream()
                .anyMatch(pos -> pos.getX() == x && pos.getZ() == z);
    }
}
