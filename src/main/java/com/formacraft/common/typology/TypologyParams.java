package com.formacraft.common.typology;

import java.util.Locale;
import java.util.Map;

/** Typed accessors for typology param maps (from registry defaults + LlmPlan params). */
public final class TypologyParams {

    private final Map<String, Object> params;

    public TypologyParams(Map<String, Object> params) {
        this.params = params != null ? params : Map.of();
    }

    public Map<String, Object> raw() {
        return params;
    }

    public int intVal(String key, int def, int min, int max) {
        int v = parseInt(params.get(key), def);
        return Math.max(min, Math.min(max, v));
    }

    public int intVal(String key, String altKey, int def, int min, int max) {
        if (params.containsKey(key)) {
            return intVal(key, def, min, max);
        }
        return intVal(altKey, def, min, max);
    }

    public boolean boolVal(String key, boolean def) {
        Object v = params.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return def;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return def;
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    public String strVal(String key, String def) {
        Object v = params.get(key);
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private static int parseInt(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return def;
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
