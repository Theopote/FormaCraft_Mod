package com.formacraft.common.generation.component.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses revolve profile curves for CROWN components and REVOLVE_SURFACE assembly ops.
 * <p>
 * Accepts LLM-friendly {@code profile_curve_y_radius} ({@code y_rel}/{@code radius}) or
 * assembly-style {@code profilePoints} ({@code x}=radius, {@code y}=height).
 */
public final class RevolveProfileParser {

    public static final String GENERATION_REVOLVED_SURFACE = "revolved_surface_around_axis";

    private RevolveProfileParser() {}

    public static List<double[]> resolve(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        Object raw = firstValue(params,
                "profile_curve_y_radius",
                "profileCurveYRadius",
                "profile_curve",
                "profileCurve",
                "crown_profile",
                "crownProfile",
                "profile_points",
                "profilePoints",
                "profileRings",
                "rings",
                "points");
        List<double[]> parsed = parseValue(raw);
        return parsed == null ? null : normalizeProfile(parsed);
    }

    /**
     * Converts a normalized {@code [radius, y]} profile into assembly {@code profilePoints} block coords.
     */
    public static List<Map<String, Object>> toAssemblyProfilePoints(
            List<double[]> normalizedProfile,
            double radiusScale,
            int heightBlocks
    ) {
        if (normalizedProfile == null || normalizedProfile.size() < 2) {
            return List.of();
        }
        int h = Math.max(1, heightBlocks);
        double rScale = Math.max(0.1, radiusScale);
        List<Map<String, Object>> out = new ArrayList<>(normalizedProfile.size());
        for (double[] pt : normalizedProfile) {
            if (pt == null || pt.length < 2) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("x", (int) Math.round(pt[0] * rScale));
            row.put("y", (int) Math.round(pt[1] * h));
            out.add(row);
        }
        return out;
    }

    static List<double[]> parseValue(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof List<?> list) {
            return parsePointList(list);
        }
        if (raw instanceof Map<?, ?> map) {
            Object nested = firstValue(castMap(map), "points", "profile", "curve", "profile_curve_y_radius");
            if (nested != null) {
                return parseValue(nested);
            }
        }
        return null;
    }

    static List<double[]> normalizeProfile(List<double[]> points) {
        if (points == null || points.size() < 2) {
            return null;
        }
        List<double[]> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingDouble(p -> p[1]));

        double maxY = 0.0;
        double maxR = 0.0;
        for (double[] pt : sorted) {
            maxY = Math.max(maxY, pt[1]);
            maxR = Math.max(maxR, pt[0]);
        }
        if (maxY <= 1e-6 || maxR <= 1e-6) {
            return null;
        }

        boolean yNormalized = maxY <= 1.001;
        boolean rNormalized = maxR <= 1.001;
        List<double[]> out = new ArrayList<>(sorted.size());
        for (double[] pt : sorted) {
            double r = rNormalized ? pt[0] : pt[0] / maxR;
            double y = yNormalized ? pt[1] : pt[1] / maxY;
            out.add(new double[] {
                    Math.max(0.0, Math.min(1.0, r)),
                    Math.max(0.0, Math.min(1.0, y))
            });
        }
        return out.size() >= 2 ? out : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static List<double[]> parsePointList(List<?> list) {
        if (list.isEmpty()) {
            return null;
        }
        if (list.getFirst() instanceof List<?>) {
            return parsePointList((List<?>) list.getFirst());
        }
        List<double[]> out = new ArrayList<>();
        for (Object item : list) {
            double[] pt = parsePoint(item);
            if (pt != null) {
                out.add(pt);
            }
        }
        return out.size() >= 2 ? out : null;
    }

    private static double[] parsePoint(Object item) {
        if (!(item instanceof Map<?, ?> map)) {
            return null;
        }
        Double radius = readNumber(map, "radius", "r", "x");
        Double y = readNumber(map, "y_rel", "yRel", "y", "height", "h");
        if (radius == null || y == null) {
            return null;
        }
        return new double[] {radius, y};
    }

    private static Double readNumber(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof Number n) {
                return n.doubleValue();
            }
            try {
                return Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // try next key
            }
        }
        return null;
    }

    private static Object firstValue(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}

