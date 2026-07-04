package com.formacraft.common.geometry.shape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从 PRIMITIVE 组件 params + dimensions 解析出的体素形体规格（M1 + M2）。
 */
public record ShapeSpec(
        ShapeKind kind,
        int width,
        int depth,
        int height,
        boolean hollow,
        int wallThickness,
        int sides,
        double rotationXDeg,
        double rotationYDeg,
        double rotationZDeg,
        double radius,
        double topRadius,
        double radiusX,
        double radiusY,
        double radiusZ,
        double sectorStartDeg,
        double sectorSweepDeg,
        String triangleMode
) {
    public double effectiveRadiusX() {
        return radiusX > 0 ? radiusX : radius;
    }

    public double effectiveRadiusY() {
        if (radiusY > 0) {
            return radiusY;
        }
        if (kind == ShapeKind.SPHERE || kind == ShapeKind.HEMISPHERE) {
            return radius;
        }
        return (height - 1) * 0.5;
    }

    public double effectiveRadiusZ() {
        return radiusZ > 0 ? radiusZ : radius;
    }

    public double halfX() {
        return (width - 1) * 0.5;
    }

    public double halfY() {
        return (height - 1) * 0.5;
    }

    public double halfZ() {
        return (depth - 1) * 0.5;
    }

    public static ShapeSpec fromParams(int width, int depth, int height, Map<String, Object> params) {
        int w = Math.max(1, width);
        int d = Math.max(1, depth);
        int h = Math.max(1, height);
        Map<String, Object> p = params != null ? params : Map.of();

        ShapeKind kind = ShapeKind.parse(str(p, "kind", "shape", "primitive"));
        boolean hollow = bool(p, "hollow", false);
        int thickness = clamp(intVal(p, "thickness", "wall_thickness", "wallThickness", 1), 1,
                Math.max(1, Math.min(w, d) / 2));
        int sides = clamp(intVal(p, "sides", "num_sides", "numSides", 6), 3, 24);

        double rotX = dbl(p, "rotation_x_deg", "rotationXDeg", "rotate_x", 0);
        double rotY = dbl(p, "rotation_y_deg", "rotationYDeg", "rotate_y", Double.NaN);
        if (Double.isNaN(rotY)) {
            rotY = dbl(p, "rotation_y", "rotationY", 0);
        }
        double rotZ = dbl(p, "rotation_z_deg", "rotationZDeg", "rotate_z", 0);

        double r = dbl(p, "radius", "r", Math.min(w, d) * 0.5);
        double topR = dbl(p, "top_radius", "topRadius", "r_top", r * 0.25);
        double rx = dbl(p, "radius_x", "radiusX", "rx", 0);
        double ry = dbl(p, "radius_y", "radiusY", "ry", 0);
        double rz = dbl(p, "radius_z", "radiusZ", "rz", 0);

        double sectorStart = dbl(p, "sector_start_deg", "sectorStartDeg", "start_angle", 0);
        double sectorSweep = dbl(p, "sector_sweep_deg", "sectorSweepDeg", "sweep_angle", 90);
        String triangleMode = str(p, "triangle_mode", "triangleMode");
        if (triangleMode == null) {
            triangleMode = "right";
        }

        return new ShapeSpec(kind, w, d, h, hollow, thickness, sides,
                rotX, rotY, rotZ, r, topR, rx, ry, rz,
                sectorStart, sectorSweep, triangleMode);
    }

    /**
     * 解析 CSG 操作链：operations[] 或 subtract{} 单操作。
     */
    @SuppressWarnings("unchecked")
    public static List<ShapeCsgOperation> parseOperations(
            int width, int depth, int height, Map<String, Object> params
    ) {
        if (params == null || params.isEmpty()) {
            return List.of();
        }
        List<ShapeCsgOperation> out = new ArrayList<>();

        Object opsObj = params.get("operations");
        if (opsObj instanceof List<?> list && !list.isEmpty()) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> m = new HashMap<>();
                raw.forEach((k, v) -> {
                    if (k != null) {
                        m.put(String.valueOf(k), v);
                    }
                });
                CsgOp op = CsgOp.parse(m.remove("op"));
                ShapeSpec spec = fromParams(width, depth, height, mergeParams(params, m));
                out.add(new ShapeCsgOperation(op, spec));
            }
            return out;
        }

        Object subtract = params.get("subtract");
        if (subtract instanceof Map<?, ?> subMap) {
            Map<String, Object> base = copyWithoutCsg(params);
            out.add(new ShapeCsgOperation(CsgOp.UNION, fromParams(width, depth, height, base)));
            Map<String, Object> sub = new HashMap<>();
            subMap.forEach((k, v) -> {
                if (k != null) {
                    sub.put(String.valueOf(k), v);
                }
            });
            out.add(new ShapeCsgOperation(CsgOp.SUBTRACT, fromParams(width, depth, height, sub)));
            return out;
        }

        return List.of();
    }

    private static Map<String, Object> mergeParams(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> merged = copyWithoutCsg(base);
        merged.putAll(override);
        return merged;
    }

    private static Map<String, Object> copyWithoutCsg(Map<String, Object> params) {
        Map<String, Object> copy = new HashMap<>(params);
        copy.remove("operations");
        copy.remove("subtract");
        copy.remove("op");
        return copy;
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
        double v = dbl(p, k1, k2, Double.NaN);
        if (!Double.isNaN(v)) {
            return v;
        }
        return dbl(p, k3, null, def);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
