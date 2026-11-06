package com.formacraft.common.typology;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.model.build.BuildingSpec;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Merge typology registry defaults with reference soft-priors and plan/LLM params.
 * Order: typology defaults → landmark soft prior → explicit plan (plan always wins).
 * No landmark hard-routing or locked replicas.
 * <p>
 * Merged maps carry {@link #PARAM_SOURCES_KEY} so builders can tell registry defaults
 * apart from plan-authored hard constraints.
 */
public final class TypologyParamResolver {

    private TypologyParamResolver() {}

    public static final String PARAM_SOURCES_KEY = "_param_sources";
    public static final String SOURCE_TYPOLOGY_DEFAULT = "TYPOLOGY_DEFAULT";
    public static final String SOURCE_LANDMARK_PRIOR = "LANDMARK_PRIOR";
    public static final String SOURCE_PLAN = "PLAN";

    public static Map<String, Object> merge(String typologyId, Component component) {
        Map<String, Object> defaults = baseDefaults(typologyId);
        Map<String, Object> merged = new LinkedHashMap<>(defaults);
        Map<String, String> sources = new LinkedHashMap<>();
        for (String key : defaults.keySet()) {
            sources.put(key, SOURCE_TYPOLOGY_DEFAULT);
        }

        String ref = component != null ? TypologyComponentRouter.extractReferenceLandmark(component) : null;
        if (ref != null && !ref.isBlank()) {
            merged.put("reference_landmark", ref);
            sources.put("reference_landmark", SOURCE_PLAN);
        }

        // Soft prior over generic typology defaults; explicit plan below overwrites.
        Map<String, Object> snapshot = new HashMap<>(merged);
        TypologyReferencePresets.apply(merged);
        for (Map.Entry<String, Object> e : merged.entrySet()) {
            String key = e.getKey();
            if (SOURCE_PLAN.equals(sources.get(key))) continue;
            if (!snapshot.containsKey(key) || !Objects.equals(snapshot.get(key), e.getValue())) {
                sources.put(key, SOURCE_LANDMARK_PRIOR);
            }
        }

        if (component != null && component.params() != null) {
            for (Map.Entry<String, Object> e : component.params().entrySet()) {
                if (e.getKey() == null || PARAM_SOURCES_KEY.equals(e.getKey())) continue;
                merged.put(e.getKey(), e.getValue());
                sources.put(e.getKey(), SOURCE_PLAN);
            }
        }
        normalizeAliases(merged);
        inheritAliasSources(merged, sources);
        merged.put("typology_id", typologyId);
        merged.put("structural_typology", typologyId);
        merged.put(PARAM_SOURCES_KEY, Map.copyOf(sources));
        return merged;
    }

    /** True when {@code key} was authored on the plan/component, not a registry/landmark default. */
    public static boolean isPlanAuthored(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return false;
        Object raw = params.get(PARAM_SOURCES_KEY);
        if (!(raw instanceof Map<?, ?> sources)) {
            // Legacy merged maps without provenance: treat presence as authored.
            for (String key : keys) {
                if (key != null && params.containsKey(key) && params.get(key) != null) return true;
            }
            return false;
        }
        for (String key : keys) {
            if (key == null) continue;
            Object src = sources.get(key);
            if (src != null && SOURCE_PLAN.equals(String.valueOf(src))) return true;
        }
        return false;
    }

    public static Map<String, Object> fromBuildingSpec(BuildingSpec spec, String typologyId) {
        Map<String, Object> defaults = baseDefaults(typologyId);
        Map<String, Object> merged = new LinkedHashMap<>(defaults);
        Map<String, String> sources = new LinkedHashMap<>();
        for (String key : defaults.keySet()) {
            sources.put(key, SOURCE_TYPOLOGY_DEFAULT);
        }
        if (spec != null && spec.getExtra() != null) {
            Object lm = spec.getExtra().get("landmark");
            if (lm != null) {
                String s = String.valueOf(lm).trim();
                if (!s.isEmpty()) {
                    merged.putIfAbsent("reference_landmark", s);
                    sources.putIfAbsent("reference_landmark", SOURCE_PLAN);
                }
            }
            Object ref = spec.getExtra().get("reference_landmark");
            if (ref != null) {
                String s = String.valueOf(ref).trim();
                if (!s.isEmpty()) {
                    merged.put("reference_landmark", s);
                    sources.put("reference_landmark", SOURCE_PLAN);
                }
            }
        }
        Map<String, Object> snapshot = new HashMap<>(merged);
        TypologyReferencePresets.apply(merged);
        for (Map.Entry<String, Object> e : merged.entrySet()) {
            String key = e.getKey();
            if (SOURCE_PLAN.equals(sources.get(key))) continue;
            if (!snapshot.containsKey(key) || !Objects.equals(snapshot.get(key), e.getValue())) {
                sources.put(key, SOURCE_LANDMARK_PRIOR);
            }
        }

        if (spec != null) {
            if (spec.getExtra() != null) {
                for (Map.Entry<String, Object> e : spec.getExtra().entrySet()) {
                    if (e.getKey() == null || PARAM_SOURCES_KEY.equals(e.getKey())) continue;
                    merged.put(e.getKey(), e.getValue());
                    sources.put(e.getKey(), SOURCE_PLAN);
                }
            }
            if ("tailiang_timber_hall".equals(typologyId) && spec.getMaterials() != null) {
                if (spec.getMaterials().getWall() != null) {
                    merged.putIfAbsent("wall_block", spec.getMaterials().getWall());
                }
                if (spec.getMaterials().getFloor() != null) {
                    merged.putIfAbsent("floor_block", spec.getMaterials().getFloor());
                }
                if (spec.getMaterials().getRoof() != null) {
                    merged.putIfAbsent("roof_block", spec.getMaterials().getRoof());
                }
                if (spec.getMaterials().getWindow() != null) {
                    merged.putIfAbsent("window_block", spec.getMaterials().getWindow());
                }
                if (spec.getMaterials().getFoundation() != null) {
                    merged.putIfAbsent("foundation_block", spec.getMaterials().getFoundation());
                }
            }
            if (spec.getHeight() > 0) {
                merged.put("height", spec.getHeight());
                sources.put("height", SOURCE_PLAN);
                if (!merged.containsKey("towerHeight")) {
                    merged.put("towerHeight", spec.getHeight());
                    sources.put("towerHeight", SOURCE_PLAN);
                }
            }
            if (spec.getFootprint() != null) {
                int w = spec.getFootprint().getWidth();
                int d = spec.getFootprint().getDepth();
                if (w > 0) {
                    merged.put("width", w);
                    sources.put("width", SOURCE_PLAN);
                }
                if (d > 0) {
                    merged.put("depth", d);
                    sources.put("depth", SOURCE_PLAN);
                }
                if (Math.max(w, d) > 0) {
                    merged.put("baseWidth", Math.max(w, d));
                    sources.put("baseWidth", SOURCE_PLAN);
                }
            }
            if (spec.getFloors() > 0) {
                merged.put("levels", spec.getFloors());
                sources.put("levels", SOURCE_PLAN);
            }
        }
        normalizeAliases(merged);
        inheritAliasSources(merged, sources);
        merged.put("typology_id", typologyId);
        merged.put("structural_typology", typologyId);
        merged.put(PARAM_SOURCES_KEY, Map.copyOf(sources));
        return merged;
    }

    /**
     * Promote common LLM snake_case keys to the camelCase / canonical names builders read.
     * Shape/size alts overwrite registry/prior defaults (LLM intent); other alts fill if absent.
     */
    static void normalizeAliases(Map<String, Object> merged) {
        if (merged == null || merged.isEmpty()) return;
        promote(merged, "footprint", "footprint_shape", "footprintShape");
        // floor_count = shaft storeys; do NOT fold eave_tiers into floors (重檐 ≠ 更多层).
        // promote from story_count etc. overwrites soft-prior levels via the sync below.
        promote(merged, "floor_count", "floorCount", "story_count", "storyCount");
        alias(merged, "floor_count", "floors", "levels");
        if (merged.containsKey("floor_count")) {
            merged.put("levels", merged.get("floor_count"));
        }
        promote(merged, "eaves_per_level", "eavesPerLevel");
        alias(merged, "eave_tiers", "eaveTiers");
        promote(merged, "baseWidth", "base_width", "basewidth");
        promote(merged, "baseDepth", "base_depth");
        promote(merged, "baseRadius", "base_radius");
        promote(merged, "hallRadius", "hall_radius");
        alias(merged, "detailLevel", "detail_level");
        // Plan/LLM wall_block and roof_block must overwrite typology registry defaults
        // (alias() skips when bodyBlock/eaveBlock already exist from defaults).
        promote(merged, "bodyBlock", "body_block", "wall_block", "wallBlock");
        promote(merged, "eaveBlock", "eave_block", "roof_block", "roofBlock");
        alias(merged, "accentBlock", "accent_block");
        alias(merged, "trimBlock", "trim_block");
        alias(merged, "reference_landmark", "referenceLandmark", "referenceLandmarkId", "landmark");
        // Explicit double_eaves / 重檐 must beat registry default eaves_per_level=1.
        if (truthy(merged.get("double_eaves")) || truthy(merged.get("doubleEaves"))) {
            Object epl = merged.get("eaves_per_level");
            if (epl == null) epl = merged.get("eavesPerLevel");
            int n = 1;
            if (epl instanceof Number num) {
                n = num.intValue();
            } else if (epl != null) {
                try {
                    n = Integer.parseInt(String.valueOf(epl).trim());
                } catch (NumberFormatException ignored) {
                    n = 1;
                }
            }
            if (n < 2) {
                merged.put("eaves_per_level", 2);
                merged.put("eavesPerLevel", 2);
            }
        }
    }

    /**
     * After alias promotion, mark canonical keys PLAN when any alias was plan-authored.
     * Registry defaults alone must not mark height as PLAN.
     */
    private static void inheritAliasSources(Map<String, Object> merged, Map<String, String> sources) {
        promoteSource(sources, "height", "towerHeight");
        promoteSource(sources, "towerHeight", "height");
        promoteSource(sources, "floor_count", "floorCount", "story_count", "storyCount", "floors", "levels");
        promoteSource(sources, "levels", "floor_count", "floorCount");
        promoteSource(sources, "eaves_per_level", "eavesPerLevel");
        promoteSource(sources, "baseWidth", "base_width", "basewidth");
        // Keys newly created by alias/promote with no source inherit from the value-donor when possible.
        for (String key : merged.keySet()) {
            if (PARAM_SOURCES_KEY.equals(key) || sources.containsKey(key)) continue;
            sources.put(key, SOURCE_TYPOLOGY_DEFAULT);
        }
    }

    private static void promoteSource(Map<String, String> sources, String canonical, String... alts) {
        if (SOURCE_PLAN.equals(sources.get(canonical))) return;
        for (String alt : alts) {
            if (SOURCE_PLAN.equals(sources.get(alt))) {
                sources.put(canonical, SOURCE_PLAN);
                return;
            }
        }
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) return b;
        if (v == null) return false;
        String s = String.valueOf(v).trim().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "重檐".equals(s);
    }

    private static void promote(Map<String, Object> merged, String canonical, String... alts) {
        for (String alt : alts) {
            if (!merged.containsKey(alt)) continue;
            Object v = merged.get(alt);
            if (v != null && !String.valueOf(v).isBlank()) {
                merged.put(canonical, v);
                return;
            }
        }
    }

    private static void alias(Map<String, Object> merged, String canonical, String... alts) {
        if (merged.containsKey(canonical)) return;
        for (String alt : alts) {
            if (merged.containsKey(alt)) {
                Object v = merged.get(alt);
                if (v != null && !String.valueOf(v).isBlank()) {
                    merged.put(canonical, v);
                    return;
                }
            }
        }
    }

    private static Map<String, Object> baseDefaults(String typologyId) {
        Map<String, Object> merged = new LinkedHashMap<>();
        StructuralTypologyRegistry.TypologyDef def = StructuralTypologyRegistry.getById(typologyId);
        if (def != null && def.defaultParams() != null) {
            merged.putAll(def.defaultParams());
        }
        return merged;
    }
}
