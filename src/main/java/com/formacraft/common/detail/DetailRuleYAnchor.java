package com.formacraft.common.detail;

import java.util.Locale;

/**
 * Y datum for detail rules — resolved relative to building minY at compile time.
 */
public enum DetailRuleYAnchor {
    FLOOR_BOUNDARY,
    BASE_TOP,
    ROOF_EAVE,
    ABSOLUTE;

    public static DetailRuleYAnchor parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ABSOLUTE;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "floor_boundary", "floor_boundary_y", "story_top", "floor_cornice" -> FLOOR_BOUNDARY;
            case "base_top", "plinth_top", "base_plinth" -> BASE_TOP;
            case "roof_eave", "eave", "wall_top", "top_ring" -> ROOF_EAVE;
            default -> ABSOLUTE;
        };
    }
}
