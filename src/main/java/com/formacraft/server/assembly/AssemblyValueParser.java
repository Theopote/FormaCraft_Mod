package com.formacraft.server.assembly;

import com.formacraft.common.logging.FcaLog;

import java.util.Locale;

/**
 * Shared coercion helpers for Assembly op parameters.
 */
public final class AssemblyValueParser {
    private AssemblyValueParser() {}

    private static final FcaLog LOG = FcaLog.of("AssemblyValueParser");

    public static int i(Object v, int def) {
        try {
            if (v instanceof Number n) return n.intValue();
            if (v != null) return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            LOG.debug("parse int failed value={} def={}", v, def);
        }
        return def;
    }

    public static double d(Object v, double def) {
        try {
            if (v instanceof Number n) return n.doubleValue();
            if (v != null) return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            LOG.debug("parse double failed value={} def={}", v, def);
        }
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
        return Math.min(v, max);
    }

    public static boolean isAuto(Object v) {
        if (v == null) return false;
        String s = String.valueOf(v).trim().toUpperCase(Locale.ROOT);
        return s.equals("AUTO") || s.equals("A") || s.equals("AUTOMATIC");
    }

    public static Integer asInt(Object v) {
        try {
            if (v instanceof Number n) return n.intValue();
            if (v == null) return null;
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return null;
            if (!s.matches("[-+]?\\d+")) return null;
            return Integer.parseInt(s);
        } catch (Exception e) {
            LOG.debug("asInt failed value={}", v);
            return null;
        }
    }
}
