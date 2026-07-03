package com.formacraft.server.assembly;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Shared helpers for {@link MetaAssemblyCompiler} phase classes.
 */
final class AssemblyCompilerUtils {
    private AssemblyCompilerUtils() {}

    static int i(Object v, int def) {
        return AssemblyValueParser.i(v, def);
    }

    static double d(Object v) {
        double parsed = AssemblyValueParser.d(v, Double.NaN);
        return parsed;
    }

    static String str(Object v, String def) {
        return AssemblyValueParser.str(v, def);
    }

    static Map<String, Object> op(String name, Object... kv) {
        Map<String, Object> o = new HashMap<>();
        o.put("op", name);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            o.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return o;
    }

    static void copy(Map<String, Object> src, Map<String, Object> dst, String key) {
        if (src.containsKey(key) && src.get(key) != null) dst.put(key, src.get(key));
    }

    static void copyInt(Map<String, Object> src, Map<String, Object> dst, String key, int def) {
        dst.put(key, i(src.get(key), def));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> safeMap(Map<?, ?> m) {
        try {
            return (Map<String, Object>) m;
        } catch (Exception e) {
            return null;
        }
    }

    static String normalizePortKey(String s) {
        if (s == null) return "center";
        String k = s.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) return "center";
        k = k.replace('-', '_').replace(' ', '_');
        if (k.equals("n")) k = "north";
        if (k.equals("s")) k = "south";
        if (k.equals("e")) k = "east";
        if (k.equals("w")) k = "west";
        if (k.equals("topcenter")) k = "top_center";
        if (k.equals("bottomcenter")) k = "bottom_center";
        if (k.equals("frontleft")) k = "front_left";
        if (k.equals("frontright")) k = "front_right";
        if (k.equals("backleft")) k = "back_left";
        if (k.equals("backright")) k = "back_right";
        return k;
    }
}
