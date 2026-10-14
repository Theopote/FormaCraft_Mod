package com.formacraft.common.compiler.postprocess;

/**
 * Per-processor write authority for post-process occupancy checks.
 */
public enum PostProcessWritePolicy {
    /** May PLACE on empty cells only; no overwrite, no carve. */
    PLACE_ONLY,
    /** May PLACE on empty or replace existing materials; no REMOVE of occupied cells. */
    REPLACE_WITHIN_SCOPE,
    /** May PLACE, replace, and REMOVE occupied cells within processor scope. */
    CARVE_WITHIN_SCOPE,
    /** Full structural rewrite (same as carve for occupancy purposes). */
    STRUCTURAL_REWRITE;

    public boolean allowsOverwrite() {
        return this != PLACE_ONLY;
    }

    public boolean allowsCarve() {
        return this == CARVE_WITHIN_SCOPE || this == STRUCTURAL_REWRITE;
    }

    public static PostProcessWritePolicy fromParam(Object raw) {
        if (raw == null) return PLACE_ONLY;
        try {
            return PostProcessWritePolicy.valueOf(String.valueOf(raw).trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PLACE_ONLY;
        }
    }
}
