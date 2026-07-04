package com.formacraft.common.geometry.shape;

import java.util.Locale;

/**
 * Voronoi 细胞维度：2D 平面挤出（默认）或 3D 体积细胞。
 */
public enum VoronoiDimension {
    PLANAR,
    VOLUME;

    public static VoronoiDimension parse(Object raw, ShapeExtrudeMode extrudeMode, int height) {
        if (raw != null) {
            String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
            if (s.equals("3d") || s.equals("volume") || s.equals("spatial") || s.equals("true") || s.equals("1")) {
                return VOLUME;
            }
            if (s.equals("2d") || s.equals("planar") || s.equals("false") || s.equals("0")) {
                return PLANAR;
            }
        }
        if (extrudeMode == ShapeExtrudeMode.PLATE) {
            return PLANAR;
        }
        return PLANAR;
    }
}
