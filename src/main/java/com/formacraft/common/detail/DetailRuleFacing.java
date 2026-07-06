package com.formacraft.common.detail;

import java.util.Locale;

public enum DetailRuleFacing {
    OUTWARD,
    NONE;

    public static DetailRuleFacing parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "outward", "out", "exterior" -> OUTWARD;
            default -> NONE;
        };
    }
}
