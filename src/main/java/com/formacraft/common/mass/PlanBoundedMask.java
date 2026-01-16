package com.formacraft.common.mass;

import net.minecraft.util.math.BlockPos;

import java.util.Set;

/**
 * PlanBoundedMask（基于 Plan Domain 的掩码）
 * <p>
 * AreaMask 的离散方块位置实现
 * <p>
 * 使用离散的方块位置集合
 * <p>
 * v1 最小实现：使用 BlockPos 集合
 */
public class PlanBoundedMask implements AreaMask {
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
