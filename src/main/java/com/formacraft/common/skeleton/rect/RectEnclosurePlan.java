package com.formacraft.common.skeleton.rect;

import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.common.skeleton.SkeletonType;
import net.minecraft.util.math.Direction;

/**
 * RectEnclosurePlan: a rectangle perimeter wall with optional gate opening.
 * Anchor convention: origin is the center of the rectangle at ground level.
 */
public final class RectEnclosurePlan implements SkeletonPlan {
    public final int width;        // X size
    public final int depth;        // Z size
    public final int wallHeight;   // Y
    public final int thickness;    // wall thickness (>=1)
    public final Direction gateSide;
    public final int gateWidth;

    public RectEnclosurePlan(int width, int depth, int wallHeight, int thickness, Direction gateSide, int gateWidth) {
        this.width = width;
        this.depth = depth;
        this.wallHeight = wallHeight;
        this.thickness = thickness;
        this.gateSide = gateSide == null ? Direction.SOUTH : gateSide;
        this.gateWidth = Math.max(1, gateWidth);
    }

    @Override
    public SkeletonType type() {
        return SkeletonType.COMPOUND;
    }
}


