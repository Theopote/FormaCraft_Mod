package com.formacraft.common.facade.rhythm;

import java.util.Locale;

/**
 * One slice of a facade repeating unit (pillar band, window band, solid band, etc.).
 */
public record RepeatingPatternElement(
        Type type,
        int width,
        int inset
) {
    public enum Type {
        PILLAR,
        WINDOW,
        SOLID,
        UNKNOWN;

        public static Type parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return UNKNOWN;
            }
            String v = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            return switch (v) {
                case "pillar", "pilaster", "column", "post" -> PILLAR;
                case "window", "opening", "glazing" -> WINDOW;
                case "solid", "wall", "blank" -> SOLID;
                default -> UNKNOWN;
            };
        }
    }

    public RepeatingPatternElement {
        width = Math.max(0, width);
        inset = Math.max(0, inset);
    }

    public boolean isPillar() {
        return type == Type.PILLAR;
    }

    public boolean isWindow() {
        return type == Type.WINDOW;
    }
}
