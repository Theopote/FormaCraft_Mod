package com.formacraft.common.geometry.shape;

import java.util.Locale;
import java.util.Map;

/**
 * 从 PRIMITIVE 组件 params + dimensions 解析出的体素形体规格。
 */
public record ShapeSpec(
        ShapeKind kind,
        int width,
        int depth,
        int height,
        boolean hollow,
        int wallThickness,
        int sides,
        double rotationYDeg,
        double radius,
        double topRadius,
        boolean filled
) {
    public static ShapeSpec fromParams(int width, int depth, int height, Map<String, Object> params) {
        int w = Math.max(1, width);
        int d = Math.max(1, depth);
        int h = Math.max(1, height);
        Map<String, Object> p = params != null ? params : Map.of();

        ShapeKind kind = ShapeKind.parse(str(p, "kind", "shape", "primitive"));
        boolean hollow = bool(p, "hollow", false);
        int thickness = clamp(intVal(p, "thickness", "wall_thickness", "wallThickness", 1), 1, Math.max(1, Math.min(w, d) / 2));
        int sides = clamp(intVal(p, "sides", "num_sides", "numSides", 6), 3, 24);
        double rot = dbl(p, "rotation_y_deg", "rotationYDeg", "rotate_y", Double.NaN);
        if (Double.isNaN(rot)) {
            rot = dbl(p, "rotation_y", "rotationY", 0);
        }
        boolean filled = !bool(p, "shell_only", false);

        double r = dbl(p, "radius", "r", Math.min(w, d) * 0.5);
        double topR = dbl(p, "top_radius", "topRadius", "r_top", r * 0.25);

        return new ShapeSpec(kind, w, d, h, hollow, thickness, sides, rot, r, topR, filled);
    }

    private static String str(Map<String, Object> p, String... keys) {
        for (String k : keys) {
            Object v = p.get(k);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    private static boolean bool(Map<String, Object> p, String key, boolean def) {
        Object v = p.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v != null) {
            String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
            if (s.equals("true") || s.equals("1") || s.equals("yes")) {
                return true;
            }
            if (s.equals("false") || s.equals("0") || s.equals("no")) {
                return false;
            }
        }
        return def;
    }

    private static int intVal(Map<String, Object> p, String k1, String k2, String k3, int def) {
        for (String k : new String[]{k1, k2, k3}) {
            if (k == null) continue;
            Object v = p.get(k);
            if (v instanceof Number n) {
                return n.intValue();
            }
            if (v != null) {
                try {
                    return Integer.parseInt(String.valueOf(v).trim());
                } catch (NumberFormatException ignored) {
                    // continue
                }
            }
        }
        return def;
    }

    private static double dbl(Map<String, Object> p, String k1, String k2, double def) {
        for (String k : new String[]{k1, k2}) {
            if (k == null) continue;
            Object v = p.get(k);
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            if (v != null) {
                try {
                    return Double.parseDouble(String.valueOf(v).trim());
                } catch (NumberFormatException ignored) {
                    // continue
                }
            }
        }
        return def;
    }

    private static double dbl(Map<String, Object> p, String k1, String k2, String k3, double def) {
        Double v = dbl(p, k1, k2, Double.NaN);
        if (!Double.isNaN(v)) {
            return v;
        }
        return dbl(p, k3, null, def);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
