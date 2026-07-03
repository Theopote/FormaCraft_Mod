package com.formacraft.server.assembly.macro;

import com.formacraft.common.logging.FcaLog;
import com.formacraft.server.assembly.validation.AssemblyValidationIssue;

import java.util.Locale;
import java.util.Map;

/** Shared helpers for macro appliers. */
final class AssemblyMacroSupport {
    static final FcaLog LOG = FcaLog.of("AssemblyMacroSupport");

    private AssemblyMacroSupport() {}

    static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        return Math.min(v, 1.0);
    }

    static double d(Object v, double def) {
        try {
            if (v instanceof Number n) return n.doubleValue();
            if (v != null) return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            LOG.debug("parse double failed value={} def={}", v, def);
        }
        return def;
    }

    static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    static int i(Object v, int def) {
        try {
            if (v instanceof Number n) return n.intValue();
            if (v != null) return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            LOG.debug("parse int failed value={} def={}", v, def);
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> safeMap(Map<?, ?> mm) {
        try {
            return (Map<String, Object>) mm;
        } catch (Exception e) {
            LOG.debug("safeMap cast failed", e);
            return null;
        }
    }

    static Integer intOrNull(Object v) {
        try {
            if (v instanceof Number n) return n.intValue();
            if (v instanceof String s) return Integer.parseInt(s.trim());
        } catch (Exception e) {
            LOG.debug("intOrNull failed value={}", v);
        }
        return null;
    }

    static String str(Object v, String def) {
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    static AssemblyValidationIssue warn(String path, String code, String msg) {
        return new AssemblyValidationIssue(path, AssemblyValidationIssue.Severity.WARNING, code, msg);
    }

    static boolean bool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return def;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    static String uniqueId(String base, java.util.Set<String> used) {
        String b = (base == null || base.isBlank()) ? "Component" : base.trim();
        if (!used.contains(b)) return b;
        for (int i = 2; i < 1000; i++) {
            String c = b + "_" + i;
            if (!used.contains(c)) return c;
        }
        return b + "_" + System.nanoTime();
    }

    static void putPortIfAbsent(Map<String, Object> ports, String name, int x, int y, int z) {
        if (ports == null || name == null || name.isBlank()) return;
        if (ports.containsKey(name)) return;
        ports.put(name, java.util.Map.of("x", x, "y", y, "z", z));
    }

    static Map<String, Object> makeRoller(String id,
                                          int ax, int ay, int az,
                                          String axis,
                                          int offset,
                                          int r,
                                          int h,
                                          Object material) {
        int x = ax;
        int z = az;
        if ("X".equals(axis)) x = ax + offset;
        else z = az + offset;
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("id", id);
        c.put("type", "CYLINDER");
        c.put("at", java.util.Map.of("x", x, "y", ay, "z", z));
        c.put("r", r);
        c.put("h", h);
        c.put("hollow", false);
        c.put("material", material);
        return c;
    }
}
