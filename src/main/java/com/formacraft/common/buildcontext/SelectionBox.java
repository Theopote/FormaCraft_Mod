package com.formacraft.common.buildcontext;

import net.minecraft.util.math.BlockPos;

/**
 * 选区盒（闭区间）。
 */
public record SelectionBox(BlockPos min, BlockPos max) {
    public SelectionBox normalized() {
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
        return new SelectionBox(nmin, nmax);
    }

    public BlockPos bottomCenter() {
        SelectionBox s = normalized();
        if (s.min == null || s.max == null) return null;
        int cx = (s.min.getX() + s.max.getX()) / 2;
        int cz = (s.min.getZ() + s.max.getZ()) / 2;
        return new BlockPos(cx, s.min.getY(), cz);
    }
}


