package com.formacraft.common.style.profile;

/**
 * StyleRules: structural preferences that influence how skeleton parts are expressed (v1).
 * Keep this small; expand as more interpreters consume it.
 */
public final class StyleRules {
    public boolean allowFlatRoof = true;
    public boolean preferSymmetry = false;
    public boolean layeredRoof = false;

    /** Suggested floor height in blocks (for multi-floor buildings). */
    public int floorHeight = 4;

    /** 0..1 density suggestion for windows/openings. */
    public float windowDensity = 0.3f;

    /** RectEnclosure cap layering (1..3). */
    public int capLayers = 1;

    /** RectEnclosure cap overhang outward in blocks (0..1). */
    public int capOverhang = 0;
}


