package com.formacraft.server.foundation;

/**
 * FoundationType (v1):
 * A coarse, deterministic footing category derived from footprint terrain variance.
 *
 * This is a "middle layer" between:
 * - ideal building body (generators)
 * - raw terrain (world)
 */
public enum FoundationType {
    FLAT_PAD,   // small variance: shallow pad, minimal edits
    STEPPED,    // medium variance: slightly stronger pad/clear (still local)
    STILT,      // large variance: minimal fill; rely on pillars/H-layer supports
    EMBEDDED    // hillside-ish: minimal fill, stronger clear/cut into terrain
}


