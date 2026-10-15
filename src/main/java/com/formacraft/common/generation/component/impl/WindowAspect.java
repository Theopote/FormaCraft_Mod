package com.formacraft.common.generation.component.impl;

import java.util.Locale;
import java.util.Optional;

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

    /** Parse raw token; blank/null → empty (caller chooses technical default). */
    public static Optional<WindowAspect> parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return Optional.of(switch (s) {
            case "horizontal_strip", "horizontal", "horizontal_band" -> HORIZONTAL_STRIP;
            case "ribbon_glazing", "ribbon", "curtain", "curtain_wall" -> RIBBON_GLAZING;
            case "vertical_strip", "vertical" -> VERTICAL_STRIP;
            case "arrow_slit", "arrow_slits", "slit", "slit_window" -> ARROW_SLIT;
            case "punch", "punch_window", "square_window" -> PUNCH_WINDOW;
            case "full_height", "floor_to_ceiling", "full", "full_glazing" -> FULL_HEIGHT;
            case "square", "default", "none" -> SQUARE;
            default -> SQUARE;
        });
    }

    public static WindowAspect parse(String raw) {
        return parseOrNull(raw).orElse(SQUARE);
    }

    /** True when explicit aspect and window_style imply incompatible opening geometry. */
    public static boolean conflictsWithWindowStyle(WindowAspect aspect, String windowStyle) {
        if (aspect == null || windowStyle == null || windowStyle.isBlank()) {
            return false;
        }
        String style = windowStyle.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if ("arrow_slit".equals(style) || "arrow_slits".equals(style)
                || "slit".equals(style) || "slit_window".equals(style)) {
            return aspect != ARROW_SLIT;
        }
        return false;
    }
}
