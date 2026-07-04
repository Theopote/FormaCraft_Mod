package com.formacraft.common.geometry.shape;

import java.util.Locale;

/**
 * M1 体素基元类型。
 */
public enum ShapeKind {
    BOX,
    CYLINDER,
    CONE,
    FRUSTUM,
    PRISM;

    public static ShapeKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return BOX;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "cube", "box", "cuboid", "rect", "rectangle" -> BOX;
            case "cylinder", "cyl", "column", "pillar" -> CYLINDER;
            case "cone", "pyramid" -> CONE;
            case "frustum", "truncated_cone", "cone_frustum" -> FRUSTUM;
            case "prism", "polygon", "regular_polygon", "hex_prism", "hexagon" -> PRISM;
            default -> BOX;
        };
    }
}
