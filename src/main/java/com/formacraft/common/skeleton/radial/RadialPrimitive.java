package com.formacraft.common.skeleton.radial;

/**
 * A single radial geometry primitive.
 *
 * Radii are in blocks.
 * - For DISK_FILL: outerRadius used, innerRadius ignored
 * - For ANNULUS_FILL: fill where innerRadius <= dist <= outerRadius
 * - For RING_OUTLINE/CYLINDER_SHELL: outerRadius used as ring radius (1-block thick)
 */
public final class RadialPrimitive {
    public final RadialPrimitiveKind kind;
    public final RadialRole role;
    public final int outerRadius;
    public final int innerRadius;
    public final int y0;
    public final int y1;

    public RadialPrimitive(RadialPrimitiveKind kind, RadialRole role, int outerRadius, int innerRadius, int y0, int y1) {
        this.kind = kind;
        this.role = role;
        this.outerRadius = outerRadius;
        this.innerRadius = innerRadius;
        this.y0 = y0;
        this.y1 = y1;
    }
}


