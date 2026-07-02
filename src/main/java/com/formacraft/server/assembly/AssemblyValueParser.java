package com.formacraft.server.assembly;

import java.util.Locale;

/**
 * Shared coercion helpers for Assembly op parameters.
 */
public final class AssemblyValueParser {
    private AssemblyValueParser() {}

    public static int i(Object v, int def) {
        try {
            if (v instanceof Number n) return n.intValue();
            if (v != null) return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception ignored) {}
        return def;
    }

    public static double d(Object v, double def) {
        try {
            if (v instanceof Number n) return n.doubleValue();
            if (v != null) return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception ignored) {}
        return def;
    }

    public static boolean bool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return def;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    public static String str(Object v, String def) {
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    public static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static double clamp(double v, double min, double max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
