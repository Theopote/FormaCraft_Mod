package com.formacraft.common.detail;

import java.util.Locale;

public enum DetailRuleActionType {
    INVERTED_STAIRS,
    SLAB,
    BLOCK;

    public static DetailRuleActionType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return BLOCK;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "inverted_stairs", "inverted_stair", "cornice_stair", "molding_stair" -> INVERTED_STAIRS;
            case "slab", "slab_band" -> SLAB;
            case "block", "replace" -> BLOCK;
            default -> BLOCK;
        };
    }
}
