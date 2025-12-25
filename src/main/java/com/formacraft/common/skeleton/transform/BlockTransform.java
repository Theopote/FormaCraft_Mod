package com.formacraft.common.skeleton.transform;

import net.minecraft.util.math.BlockPos;

/**
 * BlockTransform applies discrete transforms to positions:
 * - rotate around origin (0,0,0) in 90-degree steps (Y axis)
 * - mirror on X/Z axes
 * - translate (dx,dy,dz)
 *
 * This is used by COMPOUND skeleton to compose reusable topology pieces.
 */
public final class BlockTransform {
    public final int dx;
    public final int dy;
    public final int dz;
    public final boolean mirrorX;
    public final boolean mirrorZ;
    public final YRotation rotation;

    public BlockTransform(int dx, int dy, int dz, boolean mirrorX, boolean mirrorZ, YRotation rotation) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.mirrorX = mirrorX;
        this.mirrorZ = mirrorZ;
        this.rotation = rotation == null ? YRotation.NONE : rotation;
    }

    public static BlockTransform identity() {
        return new BlockTransform(0, 0, 0, false, false, YRotation.NONE);
    }

    public static BlockTransform translate(int dx, int dy, int dz) {
        return new BlockTransform(dx, dy, dz, false, false, YRotation.NONE);
    }

    public BlockPos apply(BlockPos p) {
        int x = p.getX();
        int y = p.getY();
        int z = p.getZ();

        if (mirrorX) x = -x;
        if (mirrorZ) z = -z;

        // rotate around origin
        int rx = x;
        int rz = z;
        switch (rotation) {
            case NONE -> { /* no-op */ }
            case CW_90 -> { rx = -z; rz = x; }
            case CW_180 -> { rx = -x; rz = -z; }
            case CW_270 -> { rx = z; rz = -x; }
        }

        return new BlockPos(rx + dx, y + dy, rz + dz);
    }
}


