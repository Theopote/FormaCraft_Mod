package com.formacraft.common.generation.component.impl;

import java.util.Locale;

/**
 * 窗洞比例/形态语法（与 proportion_cards openingGrammar 对齐）。
 */
public enum WindowAspect {
    SQUARE,
    HORIZONTAL_STRIP,
    VERTICAL_STRIP,
    RIBBON_GLAZING,
    ARROW_SLIT,
    PUNCH_WINDOW,
    FULL_HEIGHT;

    public static WindowAspect parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SQUARE;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (s) {
            case "horizontal_strip", "horizontal", "horizontal_band" -> HORIZONTAL_STRIP;
            case "ribbon_glazing", "ribbon", "curtain", "curtain_wall" -> RIBBON_GLAZING;
            case "vertical_strip", "vertical" -> VERTICAL_STRIP;
            case "arrow_slit", "arrow_slits", "slit", "slit_window" -> ARROW_SLIT;
            case "punch", "punch_window", "square_window" -> PUNCH_WINDOW;
            case "full_height", "floor_to_ceiling", "full", "full_glazing" -> FULL_HEIGHT;
            case "square", "default", "none" -> SQUARE;
            default -> SQUARE;
        };
    }
}
