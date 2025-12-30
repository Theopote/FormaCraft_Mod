package com.formacraft.server.terrain;

import java.util.Locale;

/**
 * Base level selection strategy for terrain adaptation.
 */
public enum TerrainBaseLevel {
    AVERAGE,
    MEDIAN,
    MODE,
    LOWEST,
    HIGHEST,
    FIXED;

    public static TerrainBaseLevel parse(Object v, TerrainBaseLevel def) {
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return def;
        s = s.toUpperCase(Locale.ROOT);
        try {
            return TerrainBaseLevel.valueOf(s);
        } catch (Exception ignored) {}
        if (s.contains("AVG") || s.contains("AVER")) return AVERAGE;
        if (s.contains("MED")) return MEDIAN;
        if (s.contains("MODE")) return MODE;
        if (s.contains("LOW")) return LOWEST;
        if (s.contains("HIGH")) return HIGHEST;
        if (s.contains("FIX")) return FIXED;
        return def;
    }
}


