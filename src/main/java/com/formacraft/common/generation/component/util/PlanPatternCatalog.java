package com.formacraft.common.generation.component.util;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Canonical plan_type / footprint_pattern tokens accepted when explicitly planned. */
public final class PlanPatternCatalog {
    private PlanPatternCatalog() {}

    public static final String[] PLAN_TYPE_KEYS = {
            "plan_type", "planType",
            "footprint_pattern", "footprintPattern",
            "plan_pattern", "planPattern"
    };

    /** Tokens that resolve to {@link ResolvedFootprint.PlanPattern#NONE}. */
    public static final Set<String> NONE_TOKENS = Set.of(
            "none", "rectangle", "rect", "box", "default"
    );

    public static final Set<String> CROSS_TOKENS = Set.of(
            "cross", "cruciform", "plus", "t_shape", "t-shape", "tshape"
    );

    public static final Set<String> CUT_CORNERS_TOKENS = Set.of(
            "cut_corners", "cut-corners", "cutcorner", "cut_corner",
            "chamfer", "chamfered", "octagon", "octagonal"
    );

    public static final Set<String> L_SHAPE_TOKENS = Set.of(
            "l", "l_shape", "l-shape", "lshape", "corner", "corner_wing"
    );

    public static final Set<String> COURTYARD_TOKENS = Set.of(
            "courtyard", "court", "ring", "donut", "siheyuan", "compound"
    );

    public static final Set<String> SUPPORTED_TOKENS;

    static {
        java.util.HashSet<String> all = new java.util.HashSet<>();
        all.addAll(NONE_TOKENS);
        all.addAll(CROSS_TOKENS);
        all.addAll(CUT_CORNERS_TOKENS);
        all.addAll(L_SHAPE_TOKENS);
        all.addAll(COURTYARD_TOKENS);
        SUPPORTED_TOKENS = Set.copyOf(all);
    }

    public static Optional<String> extractExplicit(Map<String, Object> params) {
        if (params == null) {
            return Optional.empty();
        }
        for (String key : PLAN_TYPE_KEYS) {
            if (!params.containsKey(key) || params.get(key) == null) {
                continue;
            }
            String value = String.valueOf(params.get(key)).trim();
            if (!value.isEmpty()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    public static boolean isSupportedExplicit(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return SUPPORTED_TOKENS.contains(raw.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Map a supported token to a plan pattern. Unsupported tokens return empty
     * (callers must not silently map to NONE).
     */
    public static Optional<ResolvedFootprint.PlanPattern> parseSupported(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(ResolvedFootprint.PlanPattern.NONE);
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TOKENS.contains(v)) {
            return Optional.empty();
        }
        if (NONE_TOKENS.contains(v)) {
            return Optional.of(ResolvedFootprint.PlanPattern.NONE);
        }
        if (CROSS_TOKENS.contains(v)) {
            return Optional.of(ResolvedFootprint.PlanPattern.CROSS);
        }
        if (CUT_CORNERS_TOKENS.contains(v)) {
            return Optional.of(ResolvedFootprint.PlanPattern.CUT_CORNERS);
        }
        if (L_SHAPE_TOKENS.contains(v)) {
            return Optional.of(ResolvedFootprint.PlanPattern.L_SHAPE);
        }
        if (COURTYARD_TOKENS.contains(v)) {
            return Optional.of(ResolvedFootprint.PlanPattern.COURTYARD);
        }
        return Optional.empty();
    }

    /** True when pattern is a non-courtyard cut that is incompatible with CIRCLE shape. */
    public static boolean requiresNonCircularBase(ResolvedFootprint.PlanPattern pattern) {
        return pattern == ResolvedFootprint.PlanPattern.CROSS
                || pattern == ResolvedFootprint.PlanPattern.CUT_CORNERS
                || pattern == ResolvedFootprint.PlanPattern.L_SHAPE;
    }
}
