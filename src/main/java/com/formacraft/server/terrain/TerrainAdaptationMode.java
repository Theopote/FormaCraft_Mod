package com.formacraft.server.terrain;

/**
 * TerrainAdaptationMode (v1):
 * A declarative "how to sit on terrain" mode, designed to be driven by LLM JSON.
 *
 * This is intentionally higher-level than the legacy TerrainPolicy and can be
 * mapped onto existing TerrainFit/TerrainShaper behaviors.
 */
public enum TerrainAdaptationMode {
    /** Keep original behavior / resolver fallback. */
    DEFAULT,

    /** Cut-and-fill: flatten the unit area to a chosen base level, then place normally. */
    FLATTEN,

    /** Drape: keep relative wall height, but shift blocks by terrain delta per (x,z). */
    DRAPE,

    /** Anchor: building stays level at a base level, add local pad and optional deep pillars downwards. */
    ANCHOR,

    /** Embed: sink the building into terrain and carve/clear the cavity. */
    EMBED,

    /** Float: place at a fixed altitude with no ground connection. */
    FLOAT
}


