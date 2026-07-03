package com.formacraft.common.style.profile;

import java.util.List;

/**
 * BlockPalette: material vocabulary for a style (v1).
 * Uses Minecraft block ids (e.g. minecraft:stone_bricks).
 */
public final class BlockPalette {
    public String wall;
    public String roof;
    public String floor;
    public String window;
    public String foundation;
    public String trim;
    public String pillar;
    /** Wall/fence cap (optional): e.g. minecraft:polished_blackstone_slab */
    public String cap;

    // Optional variants: consumed deterministically by generators via pickWall/pickRoof.
    public List<String> wallVariants;
    public List<String> roofVariants;

    /**
     * Deterministically pick a wall block id from {@link #wallVariants} using a position-derived
     * seed. Falls back to {@link #wall} when no usable variants are present, so callers can route
     * every wall cell through this method without behavioral regression.
     */
    public String pickWall(long seed) {
        return pickVariant(wall, wallVariants, seed);
    }

    /**
     * Deterministically pick a roof block id from {@link #roofVariants}. See {@link #pickWall(long)}.
     */
    public String pickRoof(long seed) {
        return pickVariant(roof, roofVariants, seed);
    }

    /**
     * Pure, deterministic variant selection. The base id participates as an implicit member so the
     * base material still shows up in the mix rather than being fully replaced by variants.
     */
    private static String pickVariant(String base, List<String> variants, long seed) {
        if (variants == null || variants.isEmpty()) {
            return base;
        }
        boolean hasBase = base != null && !base.isBlank();
        int n = variants.size() + (hasBase ? 1 : 0);
        if (n <= 0) {
            return base;
        }
        long h = (seed ^ 0x9E3779B97F4A7C15L) * 0xBF58476D1CE4E5B9L;
        int idx = (int) Long.remainderUnsigned(h >>> 1, n);
        if (hasBase) {
            if (idx == 0) {
                return base;
            }
            idx -= 1;
        }
        String v = variants.get(idx);
        return (v == null || v.isBlank()) ? base : v;
    }
}


