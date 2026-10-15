package com.formacraft.common.generation.design;

import com.formacraft.common.llm.dto.LlmPlan;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Canonical design param ids and aliases for provenance locking. */
public final class DesignParameterRegistry {
    private DesignParameterRegistry() {}

    private static final Map<String, String> ALIAS_TO_CANONICAL = Map.ofEntries(
            Map.entry("window_ratio", "window_ratio"),
            Map.entry("windowratio", "window_ratio"),
            Map.entry("window_wall_ratio", "window_ratio"),
            Map.entry("void_ratio", "void_ratio"),
            Map.entry("voidratio", "void_ratio"),
            Map.entry("roof_pitch_degrees", "roof_pitch_degrees"),
            Map.entry("roofpitchdegrees", "roof_pitch_degrees"),
            Map.entry("pitch_degrees", "roof_pitch_degrees"),
            Map.entry("window_aspect", "window_aspect"),
            Map.entry("windowaspect", "window_aspect"),
            Map.entry("roof_type", "roof_type"),
            Map.entry("rooftype", "roof_type"),
            Map.entry("entrance_type", "entrance_type"),
            Map.entry("entrancetype", "entrance_type"),
            Map.entry("window_style", "window_style"),
            Map.entry("windowstyle", "window_style"),
            Map.entry("wall_thickness", "wall_thickness"),
            Map.entry("wallthickness", "wall_thickness"),
            Map.entry("floor_count", "floor_count"),
            Map.entry("floorcount", "floor_count"),
            Map.entry("door_width", "door_width"),
            Map.entry("doorwidth", "door_width"),
            Map.entry("door_height", "door_height"),
            Map.entry("doorheight", "door_height"),
            Map.entry("rhythm_preset", "rhythm_preset"),
            Map.entry("rhythmpreset", "rhythm_preset"),
            Map.entry("facade_rhythm_preset", "rhythm_preset"),
            Map.entry("facaderhythmpreset", "rhythm_preset"),
            Map.entry("overhang", "overhang"),
            Map.entry("overhang_blocks", "overhang"),
            Map.entry("overhangblocks", "overhang"),
            Map.entry("eave_overhang", "overhang"),
            Map.entry("eaveoverhang", "overhang"),
            Map.entry("crown_template", "crown_template"),
            Map.entry("crowntemplate", "crown_template")
    );

    private static final Map<String, Set<String>> CANONICAL_ALIASES = Map.ofEntries(
            Map.entry("window_ratio", Set.of("window_ratio", "windowRatio", "window_wall_ratio")),
            Map.entry("void_ratio", Set.of("void_ratio", "voidRatio")),
            Map.entry("roof_pitch_degrees", Set.of("roof_pitch_degrees", "roofPitchDegrees", "pitch_degrees")),
            Map.entry("window_aspect", Set.of("window_aspect", "windowAspect")),
            Map.entry("roof_type", Set.of("roof_type", "roofType")),
            Map.entry("entrance_type", Set.of("entrance_type", "entranceType")),
            Map.entry("window_style", Set.of("window_style", "windowStyle")),
            Map.entry("wall_thickness", Set.of("wall_thickness", "wallThickness")),
            Map.entry("floor_count", Set.of("floor_count", "floorCount")),
            Map.entry("door_width", Set.of("door_width", "doorWidth")),
            Map.entry("door_height", Set.of("door_height", "doorHeight")),
            Map.entry("rhythm_preset", Set.of("rhythm_preset", "rhythmPreset", "facade_rhythm_preset")),
            Map.entry("overhang", Set.of("overhang", "overhang_blocks", "eave_overhang")),
            Map.entry("crown_template", Set.of("crown_template", "crownTemplate"))
    );

    public static String canonicalKey(String anyKey) {
        if (anyKey == null || anyKey.isBlank()) {
            return anyKey;
        }
        String normalized = anyKey.trim();
        String lookup = normalized.toLowerCase(Locale.ROOT).replace('-', '_');
        return ALIAS_TO_CANONICAL.getOrDefault(lookup, normalized);
    }

    public static Set<String> aliases(String canonicalOrAlias) {
        String canonical = canonicalKey(canonicalOrAlias);
        Set<String> group = CANONICAL_ALIASES.get(canonical);
        return group == null ? Set.of(canonical) : group;
    }

    public static boolean isDesignLocked(LlmPlan plan, Map<String, Object> componentParams, String anyKey) {
        String canonical = canonicalKey(anyKey);
        for (String alias : aliases(canonical)) {
            if (!DesignProvenance.canMutateParamByExactKey(plan, componentParams, alias)) {
                return true;
            }
        }
        return false;
    }

    /** Prefer the alias key that holds a locked value in component params. */
    public static String lockedHolderKey(Map<String, Object> componentParams, String canonical) {
        if (componentParams == null) {
            return canonical;
        }
        Object nested = componentParams.get("design_sources");
        if (!(nested instanceof Map<?, ?> sources)) {
            return canonical;
        }
        for (String alias : aliases(canonical)) {
            Object raw = sources.get(alias);
            DesignParamSource source = DesignParamSource.parse(raw == null ? null : String.valueOf(raw));
            if (source != null && source.isDesignLocked()) {
                return alias;
            }
        }
        return canonical;
    }
}
