package com.formacraft.server.terrain;

import com.formacraft.common.json.JsonUtil;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves TerrainAdaptationSpec from spec.extra.
 *
 * Supported:
 * - extra.terrainAdaptation: { mode, base_level/baseLevel, fixedY, padDepth, clearHeight, ... }
 * - legacy:
 *   - extra.terrainPolicy (FOLLOW/ADAPTIVE/FLATTEN_AREA/TERRAFORM)
 *   - extra.terrainSnap (boolean)
 */
public final class TerrainAdaptationResolver {
    private TerrainAdaptationResolver() {}

    public static TerrainAdaptationSpec resolve(Map<String, Object> extra) {
        TerrainAdaptationSpec def = TerrainAdaptationSpec.defaults();
        if (extra == null) return def;

        // 1) explicit terrainAdaptation object (preferred)
        Object ta = extra.get("terrainAdaptation");
        if (ta == null) ta = extra.get("terrain_adaptation"); // tolerate snake_case
        Map<String, Object> m = asMap(ta);
        if (m != null && !m.isEmpty()) {
            TerrainAdaptationMode mode = parseMode(m.get("mode"), TerrainAdaptationMode.DEFAULT);
            TerrainBaseLevel base = TerrainBaseLevel.parse(firstNonNull(m.get("baseLevel"), m.get("base_level")), def.baseLevel());
            Integer fixedY = parseInt(firstNonNull(m.get("fixedY"), m.get("fixed_y")), null);

            int padDepth = clamp(parseInt(firstNonNull(m.get("padDepth"), m.get("pad_depth")), def.padDepth()), 0, 6);
            int clearHeight = clamp(parseInt(firstNonNull(m.get("clearHeight"), m.get("clear_height")), def.clearHeight()), 0, 32);
            int foundationDepth = clamp(parseInt(firstNonNull(m.get("foundationDepth"), m.get("foundation_depth")), def.foundationDepth()), 0, 16);

            int anchorMaxDepth = clamp(parseInt(firstNonNull(m.get("anchorMaxDepth"), m.get("anchor_max_depth")), def.anchorMaxDepth()), 0, 256);
            boolean extendDown = parseBool(firstNonNull(m.get("extendDown"), m.get("extend_down"), m.get("anchorExtendDown")), def.anchorExtendDown());

            int drapeMaxStep = clamp(parseInt(firstNonNull(m.get("maxStepHeight"), m.get("max_step_height")), def.drapeMaxStep()), 0, 8);
            int embedDepth = clamp(parseInt(firstNonNull(m.get("embedDepth"), m.get("embed_depth")), def.embedDepth()), 0, 64);

            boolean allowWater = parseBool(firstNonNull(m.get("allowWaterEdit"), m.get("allow_water_edit")), def.allowWaterEdit());
            boolean allowLava = parseBool(firstNonNull(m.get("allowLavaEdit"), m.get("allow_lava_edit")), def.allowLavaEdit());

            return new TerrainAdaptationSpec(
                    mode, base, fixedY,
                    padDepth, clearHeight, foundationDepth,
                    anchorMaxDepth, extendDown,
                    drapeMaxStep, embedDepth,
                    allowWater, allowLava,
                    true
            );
        }

        // 2) legacy terrainPolicy mapping (best-effort)
        TerrainPolicy tp = TerrainPolicyResolver.resolve(extra);
        TerrainAdaptationMode mapped = switch (tp) {
            case FLATTEN_AREA, TERRAFORM -> TerrainAdaptationMode.FLATTEN;
            case ADAPTIVE -> TerrainAdaptationMode.ANCHOR; // shallow anchor (pad/clear) by default
            case FOLLOW -> TerrainAdaptationMode.DEFAULT;  // keep minimal edits and avoid implicit DRAPE for buildings
        };

        // legacy knobs (kept)
        int padDepth = clamp(parseInt(extra.get("terrainPadDepth"), def.padDepth()), 0, 6);
        int clearHeight = clamp(parseInt(extra.get("terrainClearHeight"), def.clearHeight()), 0, 32);

        // allow explicit override via "foundationType=stilt" etc is handled elsewhere (FoundationPlanner).
        return new TerrainAdaptationSpec(
                mapped,
                def.baseLevel(),
                null,
                padDepth,
                clearHeight,
                def.foundationDepth(),
                def.anchorMaxDepth(),
                false,
                def.drapeMaxStep(),
                def.embedDepth(),
                def.allowWaterEdit(),
                def.allowLavaEdit(),
                false
        );
    }

    public static boolean hasExplicit(Map<String, Object> extra) {
        if (extra == null) return false;
        return extra.get("terrainAdaptation") != null || extra.get("terrain_adaptation") != null;
    }

    private static TerrainAdaptationMode parseMode(Object v, TerrainAdaptationMode def) {
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return def;
        s = s.toUpperCase(Locale.ROOT);
        try {
            return TerrainAdaptationMode.valueOf(s);
        } catch (Exception ignored) {}
        if (s.contains("FLAT") || s.contains("CUT")) return TerrainAdaptationMode.FLATTEN;
        if (s.contains("DRAPE") || s.contains("FOLLOW_GROUND")) return TerrainAdaptationMode.DRAPE;
        if (s.contains("ANCHOR") || s.contains("PILE") || s.contains("FOUNDATION")) return TerrainAdaptationMode.ANCHOR;
        if (s.contains("EMBED") || s.contains("BURY")) return TerrainAdaptationMode.EMBED;
        if (s.contains("FLOAT") || s.contains("SKY")) return TerrainAdaptationMode.FLOAT;
        return def;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        if (v == null) return null;
        if (v instanceof Map<?, ?> mm) {
            try {
                return (Map<String, Object>) mm;
            } catch (Exception ignored) {
                return null;
            }
        }
        if (v instanceof String s) {
            String json = s.trim();
            if (json.isEmpty() || "{}".equals(json)) return null;
            try {
                return JsonUtil.fromJson(json, Map.class);
            } catch (Throwable ignored) {
                return null;
            }
        }
        // best-effort: serialize then parse
        try {
            String json = JsonUtil.toJson(v);
            if (json == null || json.isBlank() || "{}".equals(json.trim())) return null;
            return JsonUtil.fromJson(json, Map.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object firstNonNull(Object... vs) {
        if (vs == null) return null;
        for (Object v : vs) if (v != null) return v;
        return null;
    }

    private static boolean parseBool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return def;
        return s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static int parseInt(Object v, int def) {
        Integer i = parseInt(v, (Integer) null);
        return i == null ? def : i;
    }

    private static Integer parseInt(Object v, Integer def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return def;
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}


