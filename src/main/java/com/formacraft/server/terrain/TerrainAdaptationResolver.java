package com.formacraft.server.terrain;

import com.formacraft.common.json.JsonUtil;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves TerrainAdaptationSpec from spec.extra.
 * <p>
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
            TerrainAdaptationMode mode = parseMode(m.get("mode"));
            TerrainBaseLevel base = TerrainBaseLevel.parse(firstNonNull(m.get("baseLevel"), m.get("base_level")), def.baseLevel());
            Integer fixedY = parseInt(firstNonNull(m.get("fixedY"), m.get("fixed_y")));

            int padDepth = clamp(parseInt(firstNonNull(m.get("padDepth"), m.get("pad_depth")), def.padDepth()), 6);
            int clearHeight = clamp(parseInt(firstNonNull(m.get("clearHeight"), m.get("clear_height")), def.clearHeight()), 32);
            int foundationDepth = clamp(parseInt(firstNonNull(m.get("foundationDepth"), m.get("foundation_depth")), def.foundationDepth()), 16);

            int anchorMaxDepth = clamp(parseInt(firstNonNull(m.get("anchorMaxDepth"), m.get("anchor_max_depth")), def.anchorMaxDepth()), 256);
            boolean extendDown = parseBool(firstNonNull(m.get("extendDown"), m.get("extend_down"), m.get("anchorExtendDown")), def.anchorExtendDown());

            int drapeMaxStep = clamp(parseInt(firstNonNull(m.get("maxStepHeight"), m.get("max_step_height")), def.drapeMaxStep()), 8);
            int embedDepth = clamp(parseInt(firstNonNull(m.get("embedDepth"), m.get("embed_depth")), def.embedDepth()), 64);

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
        int padDepth = clamp(parseInt(extra.get("terrainPadDepth"), def.padDepth()), 6);
        int clearHeight = clamp(parseInt(extra.get("terrainClearHeight"), def.clearHeight()), 32);

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

    private static TerrainAdaptationMode parseMode(Object v) {
        if (v == null) return TerrainAdaptationMode.DEFAULT;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return TerrainAdaptationMode.DEFAULT;
        s = s.toUpperCase(Locale.ROOT);
        try {
            return TerrainAdaptationMode.valueOf(s);
        } catch (Exception ignored) {}
        if (s.contains("FLAT") || s.contains("CUT")) return TerrainAdaptationMode.FLATTEN;
        if (s.contains("DRAPE") || s.contains("FOLLOW_GROUND")) return TerrainAdaptationMode.DRAPE;
        if (s.contains("ANCHOR") || s.contains("PILE") || s.contains("FOUNDATION")) return TerrainAdaptationMode.ANCHOR;
        if (s.contains("EMBED") || s.contains("BURY")) return TerrainAdaptationMode.EMBED;
        if (s.contains("FLOAT") || s.contains("SKY")) return TerrainAdaptationMode.FLOAT;
        return TerrainAdaptationMode.DEFAULT;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        switch (v) {
            case null -> {
                return null;
            }
            case Map<?, ?> mm -> {
                try {
                    return (Map<String, Object>) mm;
                } catch (Exception ignored) {
                    return null;
                }
            }
            case String s -> {
                String json = s.trim();
                if (json.isEmpty() || "{}".equals(json)) return null;
                try {
                    return JsonUtil.fromJson(json, Map.class);
                } catch (Throwable ignored) {
                    return null;
                }
            }
            default -> {
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
        Integer i = parseInt(v);
        return i == null ? def : i;
    }

    private static Integer parseInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }
}


