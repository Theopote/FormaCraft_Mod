package com.formacraft.common.component.socket;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * FacingUtil：把 (dx,dy,dz) 按 facing 映射到世界偏移。
 *
 * 坐标约定（水平朝向）：
 * - +Y：向上
 * - +Z：forward（沿 facing）
 * - +X：right（facing.rotateYClockwise()）
 */
public final class FacingUtil {
    private FacingUtil() {}

    public static BlockPos offset(BlockPos origin, Direction facing, int dx, int dy, int dz) {
        if (origin == null) return BlockPos.ORIGIN;
        Direction f = (facing != null) ? facing : Direction.SOUTH;
        if (!f.getAxis().isHorizontal()) f = Direction.SOUTH;

        Direction right = f.rotateYClockwise();

        int ox = origin.getX()
                + right.getOffsetX() * dx
                + f.getOffsetX() * dz;
        int oy = origin.getY() + dy;
        int oz = origin.getZ()
                + right.getOffsetZ() * dx
                + f.getOffsetZ() * dz;

        return new BlockPos(ox, oy, oz);
    }
}

