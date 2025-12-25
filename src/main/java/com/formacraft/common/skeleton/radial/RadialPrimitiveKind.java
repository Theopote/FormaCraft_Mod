package com.formacraft.common.skeleton.radial;

/**
 * Primitive geometry kinds for radial (circular) skeletons.
 */
public enum RadialPrimitiveKind {
    DISK_FILL,        // filled disk at a single y
    ANNULUS_FILL,     // filled ring band at a single y (between outer and inner radius)
    RING_OUTLINE,     // one-block thick ring at a single y (radius r)
    CYLINDER_SHELL    // one-block thick ring for y0..y1 inclusive (radius r)
}


