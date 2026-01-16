package com.formacraft.common.mass;

/**
 * RectMask（矩形掩码）
 * <p>
 * AreaMask 的矩形实现
 * <p>
 * v1 最小实现：简单的矩形区域
 */
public class RectMask implements AreaMask {
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
