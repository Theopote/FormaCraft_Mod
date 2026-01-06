package com.formacraft.common.geometry.tool.symmetry;

import net.minecraft.util.math.BlockPos;

/**
 * SymmetryPlane（对称平面定义）
 * 
 * 用于定义对称轴（X 或 Z 轴）
 */
public record SymmetryPlane(
        Axis axis,   // X / Z
        int value    // x = value 或 z = value
) {
    public enum Axis {
        X, Z
    }

    /**
     * 计算镜像位置
     */
    public BlockPos mirror(BlockPos p) {
        if (p == null) return null;
        
        return switch (axis) {
            case X -> new BlockPos(
                    value * 2 - p.getX(),
                    p.getY(),
                    p.getZ()
            );
            case Z -> new BlockPos(
                    p.getX(),
                    p.getY(),
                    value * 2 - p.getZ()
            );
        };
    }
}

