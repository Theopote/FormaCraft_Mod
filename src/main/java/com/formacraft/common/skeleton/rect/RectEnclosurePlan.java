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

    /** Optional: crenels/battlements on top. */
    public final boolean battlements;
    /** Spacing of battlements along edges (>=1). */
    public final int battlementSpacing;

    /** Optional: wall banners/crests near the gate. */
    public final boolean banner;
    /** Banner color id suffix, e.g. "red" -> minecraft:red_wall_banner. */
    public final String bannerColor;

    public RectEnclosurePlan(int width, int depth, int wallHeight, int thickness, Direction gateSide, int gateWidth) {
        this.width = width;
        this.depth = depth;
        this.wallHeight = wallHeight;
        this.thickness = thickness;
        this.gateSide = gateSide == null ? Direction.SOUTH : gateSide;
        // gateWidth==0 means "no gate opening"
        this.gateWidth = Math.max(0, gateWidth);
        this.battlements = false;
        this.battlementSpacing = 2;
        this.banner = false;
        this.bannerColor = "red";
    }

    public RectEnclosurePlan(int width, int depth, int wallHeight, int thickness, Direction gateSide, int gateWidth,
                             boolean battlements, int battlementSpacing) {
        this.width = width;
        this.depth = depth;
        this.wallHeight = wallHeight;
        this.thickness = thickness;
        this.gateSide = gateSide == null ? Direction.SOUTH : gateSide;
        this.gateWidth = Math.max(0, gateWidth);
        this.battlements = battlements;
        this.battlementSpacing = Math.max(1, battlementSpacing);
        this.banner = false;
        this.bannerColor = "red";
    }

    public RectEnclosurePlan(int width, int depth, int wallHeight, int thickness, Direction gateSide, int gateWidth,
                             boolean battlements, int battlementSpacing, boolean banner, String bannerColor) {
        this.width = width;
        this.depth = depth;
        this.wallHeight = wallHeight;
        this.thickness = thickness;
        this.gateSide = gateSide == null ? Direction.SOUTH : gateSide;
        this.gateWidth = Math.max(0, gateWidth);
        this.battlements = battlements;
        this.battlementSpacing = Math.max(1, battlementSpacing);
        this.banner = banner;
        this.bannerColor = (bannerColor == null || bannerColor.isBlank()) ? "red" : bannerColor.trim().toLowerCase();
    }

    @Override
    public SkeletonType type() {
        return SkeletonType.COMPOUND;
    }
}


