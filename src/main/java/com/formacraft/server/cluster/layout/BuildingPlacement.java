package com.formacraft.server.cluster.layout;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * Final placement output.
 *
 * originRel is relative to cluster origin and represents the min corner of the footprint.
 */
public final class BuildingPlacement {
    public final BuildingUnit unit;
    public final BlockPos originRel;
    public final int rotation; // 0/90/180/270

    public BuildingPlacement(BuildingUnit unit, BlockPos originRel, int rotation) {
        this.unit = unit;
        this.originRel = originRel;
        this.rotation = rotation;
    }

    public int effectiveWidth() {
        return (rotation % 180 == 0) ? unit.width : unit.depth;
    }

    public int effectiveDepth() {
        return (rotation % 180 == 0) ? unit.depth : unit.width;
    }

    /**
     * Axis-aligned bounding box in relative coordinates.
     * Y is included for completeness (0..height).
     */
    public Box getBox() {
        int w = effectiveWidth();
        int d = effectiveDepth();
        int x0 = originRel.getX();
        int y0 = originRel.getY();
        int z0 = originRel.getZ();
        return new Box(x0, y0, z0, x0 + w, y0 + unit.height, z0 + d);
    }
}


