package com.formacraft.common.model.constraint;

import net.minecraft.util.math.BlockPos;

/**
 * 禁区/保护区：一个轴对齐盒（包含边界）。
 * <p>
 * 约定：min/max 为世界坐标；contains 使用闭区间判断。
 */
public record ProtectedZone(BlockPos min, BlockPos max) {
    public ProtectedZone {
        // 允许 null（防御式），但工具/网络层应尽量不传 null
    }

    public ProtectedZone normalized() {
        if (min == null || max == null) return this;
        BlockPos nmin = new BlockPos(
                Math.min(min.getX(), max.getX()),
                Math.min(min.getY(), max.getY()),
                Math.min(min.getZ(), max.getZ())
        );
        BlockPos nmax = new BlockPos(
                Math.max(min.getX(), max.getX()),
                Math.max(min.getY(), max.getY()),
                Math.max(min.getZ(), max.getZ())
        );
        return new ProtectedZone(nmin, nmax);
    }

    public boolean contains(BlockPos p) {
        if (p == null || min == null || max == null) return false;
        ProtectedZone z = normalized();
        BlockPos a = z.min();
        BlockPos b = z.max();
        return p.getX() >= a.getX() && p.getX() <= b.getX()
                && p.getY() >= a.getY() && p.getY() <= b.getY()
                && p.getZ() >= a.getZ() && p.getZ() <= b.getZ();
    }
}


