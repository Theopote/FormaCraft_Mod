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
    private static final String[] HEIGHT_KEYS = ComponentParamParsers.keysFor(
            "roof_height", "roofHeight", "roofHeightBlocks");
    private static final String[] PITCH_KEYS = ComponentParamParsers.keysFor(
            "roof_pitch_degrees", "pitch_degrees");

    /**
     * @param width footprint width
     * @param depth footprint depth
     * @param dimensionHeight Dimensions.height when already planned
     * @param params component params (may contain roof_height / roof_pitch_degrees)
     * @return rise in blocks (>= 1)
     * @throws IllegalArgumentException when an explicit rise param is present but invalid
     */
    public static int resolveRise(int width, int depth, int dimensionHeight, Map<String, Object> params) {
        Optional<String> invalid = validateRoofGeometryParams(width, depth, params);
        if (invalid.isPresent()) {
            throw new IllegalArgumentException(invalid.get());
        }

        Integer explicitHeight = rawIntIfPresent(params, HEIGHT_KEYS);
        if (explicitHeight != null && explicitHeight > 0) {
            return Math.max(1, explicitHeight);
        }

        Double pitch = rawDoubleIfPresent(params, PITCH_KEYS);
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

    /**
     * Validate explicit roof rise params: presence implies numeric validity, positive height,
     * pitch in (0, 89), and mutual consistency when both are set.
     */
    public static Optional<String> validateRoofGeometryParams(int width, int depth, Map<String, Object> params) {
        if (params == null) {
            return Optional.empty();
        }
        boolean heightPresent = hasRawKey(params, HEIGHT_KEYS);
        boolean pitchPresent = hasRawKey(params, PITCH_KEYS);
        if (!heightPresent && !pitchPresent) {
            return Optional.empty();
        }

        Integer explicitHeight = null;
        if (heightPresent) {
            explicitHeight = rawIntIfPresent(params, HEIGHT_KEYS);
            if (explicitHeight == null) {
                return Optional.of("roof_height is not a valid number");
            }
            if (explicitHeight <= 0) {
                return Optional.of("roof_height must be > 0, got " + explicitHeight);
            }
        }

        Double pitch = null;
        if (pitchPresent) {
            pitch = rawDoubleIfPresent(params, PITCH_KEYS);
            if (pitch == null) {
                return Optional.of("roof_pitch_degrees is not a valid number");
            }
            if (pitch <= 0 || pitch >= 89.0) {
                return Optional.of("roof_pitch_degrees must be in (0, 89), got " + pitch);
            }
        }

        if (explicitHeight != null && pitch != null) {
            return validateConsistency(width, depth, params);
        }
        return Optional.empty();
    }

    /** When both roof_height and roof_pitch_degrees are explicit, they must agree within tolerance. */
    public static Optional<String> validateConsistency(int width, int depth, Map<String, Object> params) {
        if (params == null) {
            return Optional.empty();
        }
        Integer explicitHeight = rawIntIfPresent(params, HEIGHT_KEYS);
        Double pitch = rawDoubleIfPresent(params, PITCH_KEYS);
        if (explicitHeight == null || explicitHeight <= 0 || pitch == null || pitch <= 0 || pitch >= 89.0) {
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
        if (hasRawKey(params, HEIGHT_KEYS) || hasRawKey(params, PITCH_KEYS)) {
            return true;
        }
        return false;
    }

    private static boolean hasRawKey(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) {
            return false;
        }
        for (String key : keys) {
            if (key != null && params.containsKey(key) && params.get(key) != null) {
                return true;
            }
        }
        return false;
    }

    private static Integer rawIntIfPresent(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || !params.containsKey(key) || params.get(key) == null) {
                continue;
            }
            Object v = params.get(key);
            if (v instanceof Number n) {
                return n.intValue();
            }
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Double rawDoubleIfPresent(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || !params.containsKey(key) || params.get(key) == null) {
                continue;
            }
            Object v = params.get(key);
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            try {
                return Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
