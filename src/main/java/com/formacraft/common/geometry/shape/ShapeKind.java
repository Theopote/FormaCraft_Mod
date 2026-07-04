package com.formacraft.common.geometry.shape;

import java.util.Locale;

/**
 * M1 + M2 + M3 体素基元类型。
 */
public enum ShapeKind {
    BOX,
    CYLINDER,
    CONE,
    FRUSTUM,
    PRISM,
    /** M2：球 / 椭球 */
    SPHERE,
    HEMISPHERE,
    /** M2：2D  footprint 沿 Y 挤出 */
    ELLIPSE,
    SECTOR,
    TRIANGLE,
    /** M3：Voronoi 细胞 / Möbius 带体素近似 */
    VORONOI,
    MOBIUS;

    public static ShapeKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return BOX;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "cube", "box", "cuboid", "rect", "rectangle" -> BOX;
            case "cylinder", "cyl", "column", "pillar", "circle" -> CYLINDER;
            case "cone", "pyramid" -> CONE;
            case "frustum", "truncated_cone", "cone_frustum" -> FRUSTUM;
            case "prism", "polygon", "regular_polygon", "hex_prism", "hexagon" -> PRISM;
            case "sphere", "ball", "orb", "spherical" -> SPHERE;
            case "hemisphere", "dome", "half_sphere" -> HEMISPHERE;
            case "ellipse", "elliptical", "oval" -> ELLIPSE;
            case "sector", "pie", "fan", "arc_sector" -> SECTOR;
            case "triangle", "tri" -> TRIANGLE;
            case "voronoi", "cells", "cellular", "honeycomb" -> VORONOI;
            case "mobius", "möbius", "moebius", "mobius_strip" -> MOBIUS;
            default -> BOX;
        };
    }
}
