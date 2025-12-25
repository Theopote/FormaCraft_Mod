package com.formacraft.server.terrain;

import java.util.Locale;
import java.util.Map;

/**
 * ZoneTerrainRule (v1):
 * Optional per-zone-type overrides for cluster terrain defaults.
 *
 * Example (spec.extra):
 * zoneTerrainRules: {
 *   "PLAZA": { "policy": "engineered", "budget": 50000, "allowWaterEdit": true },
 *   "RESIDENTIAL": { "policy": "preserve", "budget": 20000 }
 * }
 */
public record ZoneTerrainRule(
        String zoneTypeUpper,
        ClusterTerrainPolicy policy,
        Integer localBudgetBlocks,
        Boolean allowWaterEdit,
        Boolean allowLavaEdit
) {
    public static java.util.Map<String, ZoneTerrainRule> fromExtra(Map<String, Object> extra) {
        if (extra == null) return java.util.Map.of();
        Object v = extra.get("zoneTerrainRules");
        if (!(v instanceof Map<?, ?> m)) return java.util.Map.of();

        java.util.Map<String, ZoneTerrainRule> out = new java.util.HashMap<>();
        for (var e : m.entrySet()) {
            String key = e.getKey() != null ? String.valueOf(e.getKey()).trim() : "";
            if (key.isEmpty()) continue;
            String zoneType = key.toUpperCase(Locale.ROOT);
            if (!(e.getValue() instanceof Map<?, ?> mm)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = (Map<String, Object>) mm;
            ClusterTerrainPolicy p = parsePolicy(cfg.get("policy"));
            Integer budget = parseInt(cfg.get("budget"));
            Boolean water = parseBool(cfg.get("allowWaterEdit"));
            Boolean lava = parseBool(cfg.get("allowLavaEdit"));
            out.put(zoneType, new ZoneTerrainRule(zoneType, p, budget, water, lava));
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    public static ZoneTerrainRule defaultsForZoneType(String zoneTypeUpper) {
        if (zoneTypeUpper == null) return null;
        String z = zoneTypeUpper.toUpperCase(Locale.ROOT);
        return switch (z) {
            case "PLAZA", "INDUSTRIAL" -> new ZoneTerrainRule(z, ClusterTerrainPolicy.ENGINEERED, null, null, null);
            case "MARKET", "COMMERCIAL" -> new ZoneTerrainRule(z, ClusterTerrainPolicy.BALANCED, null, null, null);
            case "WALL", "GATE" -> new ZoneTerrainRule(z, ClusterTerrainPolicy.BALANCED, null, null, null);
            case "RESIDENTIAL" -> new ZoneTerrainRule(z, ClusterTerrainPolicy.PRESERVE_DOMINANT, null, null, null);
            default -> new ZoneTerrainRule(z, null, null, null, null);
        };
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

    private static Integer parseInt(Object v) {
        if (v == null) return null;
        try {
            if (v instanceof Number n) return n.intValue();
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return null;
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Boolean parseBool(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }
}


