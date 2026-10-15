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
            Map.entry("windowaspect", "window_aspect")
    );

    private static final Map<String, Set<String>> CANONICAL_ALIASES = Map.of(
            "window_ratio", Set.of("window_ratio", "windowRatio", "window_wall_ratio"),
            "void_ratio", Set.of("void_ratio", "voidRatio"),
            "roof_pitch_degrees", Set.of("roof_pitch_degrees", "roofPitchDegrees", "pitch_degrees"),
            "window_aspect", Set.of("window_aspect", "windowAspect")
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
