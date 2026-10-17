package com.formacraft.common.generation.component.util;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Canonical shape / footprint_shape tokens accepted when explicitly planned. */
public final class FootprintShapeCatalog {
    private FootprintShapeCatalog() {}

    public static final String[] SHAPE_KEYS = {
            "shape", "footprint_shape", "footprintShape"
    };

    public static final Set<String> RECTANGLE_TOKENS = Set.of(
            "rectangle", "rect", "box", "square"
    );

    public static final Set<String> CIRCLE_TOKENS = Set.of(
            "circle", "circular", "round", "cylinder"
    );

    public static final Set<String> ROUNDED_RECT_TOKENS = Set.of(
            "rounded_rect", "rounded", "roundrect", "round_rect"
    );

    public static final Set<String> SUPPORTED_TOKENS;

    static {
        java.util.HashSet<String> all = new java.util.HashSet<>();
        all.addAll(RECTANGLE_TOKENS);
        all.addAll(CIRCLE_TOKENS);
        all.addAll(ROUNDED_RECT_TOKENS);
        SUPPORTED_TOKENS = Set.copyOf(all);
    }

    public static Optional<String> extractExplicit(Map<String, Object> params) {
        if (params == null) {
            return Optional.empty();
        }
        for (String key : SHAPE_KEYS) {
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
     * Map a supported token to a footprint shape. Unsupported tokens return empty
     * (callers must not silently map to RECTANGLE).
     */
    public static Optional<ResolvedFootprint.FootprintShape> parseSupported(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(ResolvedFootprint.FootprintShape.RECTANGLE);
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TOKENS.contains(v)) {
            return Optional.empty();
        }
        if (CIRCLE_TOKENS.contains(v)) {
            return Optional.of(ResolvedFootprint.FootprintShape.CIRCLE);
        }
        if (ROUNDED_RECT_TOKENS.contains(v)) {
            return Optional.of(ResolvedFootprint.FootprintShape.ROUNDED_RECT);
        }
        return Optional.of(ResolvedFootprint.FootprintShape.RECTANGLE);
    }
}
