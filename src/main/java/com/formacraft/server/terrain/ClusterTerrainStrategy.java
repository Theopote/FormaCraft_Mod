package com.formacraft.server.terrain;

import java.util.Locale;
import java.util.Map;

/**
 * ClusterTerrainStrategy (v1):
 * Parsed from a shared "extra" map (e.g., the first structure spec.extra in a CitySpec).
 *
 * This drives defaults for:
 * - overall disturbance budget
 * - whether water/lava edits are allowed
 * - default per-unit pad/clear intensity (if unit doesn't override)
 */
public record ClusterTerrainStrategy(
        ClusterTerrainPolicy policy,
        int clusterTerrainBudgetBlocks,
        boolean allowWaterEdit,
        boolean allowLavaEdit
) {
    public static ClusterTerrainStrategy fromExtra(Map<String, Object> extra) {
        ClusterTerrainPolicy p = parsePolicy(extra != null ? extra.get("clusterTerrainPolicy") : null);
        if (p == null) p = ClusterTerrainPolicy.BALANCED;

        int budget = getInt(extra, "clusterTerrainBudgetBlocks", -1);
        if (budget > 0) budget = Math.max(0, Math.min(2_000_000, budget));

        boolean allowWater = getBool(extra, "allowWaterEdit", true);
        boolean allowLava = getBool(extra, "allowLavaEdit", true);

        return new ClusterTerrainStrategy(p, budget, allowWater, allowLava);
    }

    private static ClusterTerrainPolicy parsePolicy(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        return switch (s) {
            case "preserve", "preserve_dominant", "dominant", "follow" -> ClusterTerrainPolicy.PRESERVE_DOMINANT;
            case "balanced", "adaptive" -> ClusterTerrainPolicy.BALANCED;
            case "engineered", "engineering", "flatten" -> ClusterTerrainPolicy.ENGINEERED;
            default -> null;
        };
    }

    private static boolean getBool(Map<String, Object> extra, String key, boolean def) {
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return def;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static int getInt(Map<String, Object> extra, String key, int def) {
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        try {
            if (v instanceof Number n) return n.intValue();
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return def;
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return def;
        }
    }
}


