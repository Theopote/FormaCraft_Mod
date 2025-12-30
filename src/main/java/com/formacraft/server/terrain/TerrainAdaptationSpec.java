package com.formacraft.server.terrain;

/**
 * TerrainAdaptationSpec (v1):
 * A compact, execution-facing config derived from spec.extra.terrainAdaptation (or legacy keys).
 *
 * Notes:
 * - "explicit" means user/LLM explicitly asked for a mode; router/executor should prefer it.
 * - Defaults are chosen to preserve existing behavior unless the user opts in.
 */
public record TerrainAdaptationSpec(
        TerrainAdaptationMode mode,
        TerrainBaseLevel baseLevel,
        Integer fixedY,
        int padDepth,
        int clearHeight,
        int foundationDepth,
        int anchorMaxDepth,
        boolean anchorExtendDown,
        int drapeMaxStep,
        int embedDepth,
        boolean allowWaterEdit,
        boolean allowLavaEdit,
        boolean explicit
) {
    public static TerrainAdaptationSpec defaults() {
        return new TerrainAdaptationSpec(
                TerrainAdaptationMode.DEFAULT,
                TerrainBaseLevel.MEDIAN,
                null,
                2,
                6,
                3,
                48,
                false,
                2,
                6,
                true,
                true,
                false
        );
    }
}


