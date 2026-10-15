package com.formacraft.common.generation.component.util;

import java.util.Map;
import java.util.Optional;

/**
 * Deterministic roof rise from explicit design params.
 * Prefer {@code roof_height} / {@code roof_pitch_degrees} over span/3 technical defaults.
 */
public final class RoofPitchGeometry {
    private RoofPitchGeometry() {}

    public static final int TECHNICAL_MAX_RISE = 8;
    private static final int CONSISTENCY_TOLERANCE_BLOCKS = 1;

    /**
     * @param width footprint width
     * @param depth footprint depth
     * @param dimensionHeight Dimensions.height when already planned
     * @param params component params (may contain roof_height / roof_pitch_degrees)
     * @return rise in blocks (>= 1)
     */
    public static int resolveRise(int width, int depth, int dimensionHeight, Map<String, Object> params) {
        int explicitHeight = ComponentParamParsers.intParam(params,
                ComponentParamParsers.keysFor("roof_height", "roofHeight", "roofHeightBlocks"));
        if (explicitHeight > 0) {
            return Math.max(1, explicitHeight);
        }

        Double pitch = ComponentParamParsers.doubleOrNull(params,
                ComponentParamParsers.keysFor("roof_pitch_degrees", "pitch_degrees"));
        if (pitch != null && pitch > 0 && pitch < 89.0) {
            int span = Math.max(2, Math.min(Math.max(2, width), Math.max(2, depth)));
            double halfSpan = span / 2.0;
            int fromPitch = (int) Math.round(halfSpan * Math.tan(Math.toRadians(pitch)));
            return Math.max(2, fromPitch);
        }

        if (dimensionHeight > 0) {
            return dimensionHeight;
        }

        int span = Math.max(2, Math.min(Math.max(2, width), Math.max(2, depth)));
        return Math.max(2, Math.min(TECHNICAL_MAX_RISE, Math.max(2, span / 3)));
    }

    /** When both roof_height and roof_pitch_degrees are explicit, they must agree within tolerance. */
    public static Optional<String> validateConsistency(int width, int depth, Map<String, Object> params) {
        if (params == null) {
            return Optional.empty();
        }
        int explicitHeight = ComponentParamParsers.intParam(params,
                ComponentParamParsers.keysFor("roof_height", "roofHeight", "roofHeightBlocks"));
        Double pitch = ComponentParamParsers.doubleOrNull(params,
                ComponentParamParsers.keysFor("roof_pitch_degrees", "pitch_degrees"));
        if (explicitHeight <= 0 || pitch == null || pitch <= 0 || pitch >= 89.0) {
            return Optional.empty();
        }
        int span = Math.max(2, Math.min(Math.max(2, width), Math.max(2, depth)));
        double halfSpan = span / 2.0;
        int fromPitch = Math.max(2, (int) Math.round(halfSpan * Math.tan(Math.toRadians(pitch))));
        if (Math.abs(explicitHeight - fromPitch) > CONSISTENCY_TOLERANCE_BLOCKS) {
            return Optional.of("roof_height=" + explicitHeight + " conflicts with roof_pitch_degrees="
                    + pitch + " (implies rise " + fromPitch + ")");
        }
        return Optional.empty();
    }

    /** True when height/pitch is explicitly planned (must not apply technical cap). */
    public static boolean hasExplicitRiseIntent(Map<String, Object> params) {
        if (params == null) {
            return false;
        }
        if (ComponentParamParsers.intParam(params,
                ComponentParamParsers.keysFor("roof_height", "roofHeight", "roofHeightBlocks")) > 0) {
            return true;
        }
        Double pitch = ComponentParamParsers.doubleOrNull(params,
                ComponentParamParsers.keysFor("roof_pitch_degrees", "pitch_degrees"));
        return pitch != null && pitch > 0;
    }
}
