package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.common.typology.TypologyParams;

import java.util.Locale;
import java.util.Map;

/**
 * Separates shaft storeys from eave bands for Chinese dense-eaves / pavilion towers.
 * <ul>
 *   <li>{@code floor_count} — recognizable tower body segments (三层 → 3)</li>
 *   <li>{@code eaves_per_level} — eave rings per storey (重檐 → 2)</li>
 *   <li>{@code eave_tiers} — optional total eave rings; never silently becomes floor_count</li>
 * </ul>
 */
public record PagodaVerticalSemantics(
        int floorCount,
        int eavesPerLevel,
        int eaveTiers,
        boolean floorsExplicit
) {
    public static PagodaVerticalSemantics resolve(TypologyParams p, int implicitFloors, int implicitMin, int maxFloors) {
        if (p == null) {
            int floors = clamp(implicitFloors, implicitMin, maxFloors);
            return new PagodaVerticalSemantics(floors, 1, floors, false);
        }
        Map<String, Object> raw = p.raw();
        boolean floorsExplicit = has(raw, "floor_count", "floorCount", "floors", "story_count", "storyCount", "levels");
        int floorCount;
        if (has(raw, "floor_count", "floorCount")) {
            floorCount = readInt(p, "floor_count", "floorCount", implicitFloors);
        } else if (has(raw, "floors")) {
            floorCount = p.intVal("floors", implicitFloors, 1, maxFloors);
        } else if (has(raw, "story_count", "storyCount")) {
            floorCount = readInt(p, "story_count", "storyCount", implicitFloors);
        } else if (has(raw, "levels")) {
            // Legacy: levels meant storeys for this typology, not total eaves.
            floorCount = p.intVal("levels", implicitFloors, 1, maxFloors);
        } else if (has(raw, "eave_tiers", "eaveTiers") && !floorsExplicit) {
            // Classic dense eaves: authored eave_tiers alone ≈ one eave per storey.
            floorCount = readInt(p, "eave_tiers", "eaveTiers", implicitFloors);
            floorsExplicit = true;
        } else {
            floorCount = implicitFloors;
            floorsExplicit = false;
        }
        // Explicit floor_count is a hard authoring constraint — never silently bump 2→3.
        int minFloors = floorsExplicit ? 1 : implicitMin;
        floorCount = clamp(floorCount, minFloors, maxFloors);

        // Explicit false must win — fixtures author double_eaves=false with eaves_per_level=1.
        boolean doubleEavesDenied = isExplicitFalse(raw.get("double_eaves"))
                || isExplicitFalse(raw.get("doubleEaves"));
        boolean wantsDoubleEaves = !doubleEavesDenied
                && (truthy(raw.get("double_eaves")) || truthy(raw.get("doubleEaves"))
                || containsCue(raw, "重檐", "double_eave", "double-eave"));
        boolean eavesPerLevelAuthored = has(raw, "eaves_per_level", "eavesPerLevel");
        int eavesPerLevel = 1;
        if (eavesPerLevelAuthored) {
            eavesPerLevel = p.intVal("eaves_per_level", 1, 1, 3);
        } else if (wantsDoubleEaves) {
            eavesPerLevel = 2;
        } else if (floorsExplicit && has(raw, "eave_tiers", "eaveTiers")
                && has(raw, "floor_count", "floorCount", "floors", "levels", "story_count", "storyCount")) {
            int authoredEaves = readInt(p, "eave_tiers", "eaveTiers", floorCount);
            eavesPerLevel = Math.max(1, Math.min(3, Math.round(authoredEaves / (float) Math.max(1, floorCount))));
        }
        // Registry defaults often inject eaves_per_level=1 alongside plan double_eaves;
        // an explicit double_eaves / 重檐 request must still upgrade that default to 2.
        // Authored eaves_per_level≥2 and explicit double_eaves=false are already respected above.
        if (wantsDoubleEaves) {
            eavesPerLevel = Math.max(eavesPerLevel, 2);
        }

        int eaveTiers = floorCount * eavesPerLevel;
        return new PagodaVerticalSemantics(floorCount, eavesPerLevel, eaveTiers, floorsExplicit);
    }

    private static int readInt(TypologyParams p, String primary, String alt, int def) {
        if (p.raw().containsKey(primary) && p.raw().get(primary) != null) {
            return p.intVal(primary, def, 1, 64);
        }
        return p.intVal(alt, def, 1, 64);
    }

    private static boolean has(Map<String, Object> raw, String... keys) {
        if (raw == null) return false;
        for (String key : keys) {
            if (raw.containsKey(key) && raw.get(key) != null) return true;
        }
        return false;
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) return b;
        if (v == null) return false;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "重檐".equals(s);
    }

    private static boolean isExplicitFalse(Object v) {
        if (v instanceof Boolean b) return !b;
        if (v == null) return false;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return "false".equals(s) || "0".equals(s) || "no".equals(s);
    }

    /**
     * Scan scalar / string-list param values only. Nested maps (e.g. {@code _param_sources})
     * must not be stringified — {@code {double_eaves=LLM_INFERRED}} falsely contains
     * the cue {@code double_eave} even when the authored flag is false.
     */
    private static boolean containsCue(Map<String, Object> raw, String... cues) {
        if (raw == null) return false;
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (e.getKey() != null && TypologyParamResolver.PARAM_SOURCES_KEY.equals(e.getKey())) {
                continue;
            }
            if (valueContainsCue(e.getValue(), cues)) return true;
        }
        return false;
    }

    private static boolean valueContainsCue(Object v, String... cues) {
        if (v == null || v instanceof Map<?, ?>) return false;
        if (v instanceof Iterable<?> it && !(v instanceof CharSequence)) {
            for (Object item : it) {
                if (valueContainsCue(item, cues)) return true;
            }
            return false;
        }
        String s = String.valueOf(v).toLowerCase(Locale.ROOT);
        for (String cue : cues) {
            if (cue != null && !cue.isEmpty() && s.contains(cue.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
