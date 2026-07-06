package com.formacraft.common.detail;

import java.util.Locale;

/**
 * Spatial region for a detail rule ({@code layer_edge} → {@link #PERIMETER}).
 */
public enum DetailRuleRegion {
    PERIMETER,
    ALL;

    public static DetailRuleRegion parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PERIMETER;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "all", "everywhere" -> ALL;
            case "perimeter", "layer_edge", "edge", "exterior", "facade" -> PERIMETER;
            default -> PERIMETER;
        };
    }
}
