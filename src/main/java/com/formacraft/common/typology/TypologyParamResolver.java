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
 * Merged maps carry {@link #PARAM_SOURCES_KEY}. {@link #SOURCE_PLAN} remains as a legacy
 * lineage tag; hard geometry constraints use {@link #SOURCE_USER_EXPLICIT} only.
 * LLM-filled plan values are {@link #SOURCE_LLM_INFERRED} and may be adjusted for feasibility.
 */
public final class TypologyParamResolver {

    private TypologyParamResolver() {}

    public static final String PARAM_SOURCES_KEY = "_param_sources";
    public static final String SOURCE_TYPOLOGY_DEFAULT = "TYPOLOGY_DEFAULT";
    public static final String SOURCE_LANDMARK_PRIOR = "LANDMARK_PRIOR";
    /** Legacy lineage tag — prefer {@link #SOURCE_LLM_INFERRED} / {@link #SOURCE_USER_EXPLICIT} for new code. */
    public static final String SOURCE_PLAN = "PLAN";
    public static final String SOURCE_LLM_INFERRED = "LLM_INFERRED";
    public static final String SOURCE_USER_EXPLICIT = "USER_EXPLICIT";

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
            sources.put("reference_landmark", SOURCE_LLM_INFERRED);
        }

        // Soft prior over generic typology defaults; explicit plan below overwrites.
        Map<String, Object> snapshot = new HashMap<>(merged);
        TypologyReferencePresets.apply(merged);
        for (Map.Entry<String, Object> e : merged.entrySet()) {
            String key = e.getKey();
            if (isPlanLikeSource(sources.get(key))) continue;
            if (!snapshot.containsKey(key) || !Objects.equals(snapshot.get(key), e.getValue())) {
                sources.put(key, SOURCE_LANDMARK_PRIOR);
            }
        }

        if (component != null && component.params() != null) {
            // Defense in depth: never copy unsealed LLM forged verification maps into merged params.
            Map<String, Object> incoming = new LinkedHashMap<>(component.params());
            boolean sealed = UserConstraintContract.hasServerSeal(incoming);
            if (!sealed) {
                UserConstraintContract.stripUntrustedMarkers(incoming);
            }
            for (Map.Entry<String, Object> e : incoming.entrySet()) {
                if (e.getKey() == null || PARAM_SOURCES_KEY.equals(e.getKey())) continue;
                if (UserConstraintContract.SERVER_SEAL_KEY.equals(e.getKey())) {
                    if (sealed) merged.put(e.getKey(), e.getValue());
                    continue;
                }
                if (UserConstraintContract.SERVER_VERIFIED_SOURCES_KEY.equals(e.getKey())) {
                    if (sealed) merged.put(e.getKey(), e.getValue());
                    continue;
                }
                merged.put(e.getKey(), e.getValue());
                sources.put(e.getKey(), resolveComponentParamSource(incoming, e.getKey()));
            }
        }
        normalizeAliases(merged, sources);
        merged.put("typology_id", typologyId);
        merged.put("structural_typology", typologyId);
        merged.put(PARAM_SOURCES_KEY, Map.copyOf(sources));
        return merged;
    }

    /**
     * True when {@code key} came from the plan/component (LLM or player), not a registry/landmark default.
     * Does <em>not</em> mean the value is a player hard constraint — use {@link #isUserHardConstraint}.
     */
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
            if (isPlanLikeSource(sources.get(key))) return true;
        }
        return false;
    }

    /** True when the player explicitly required this param — geometry must not silently rewrite it. */
    public static boolean isUserHardConstraint(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return false;
        Object raw = params.get(PARAM_SOURCES_KEY);
        if (!(raw instanceof Map<?, ?> sources)) return false;
        for (String key : keys) {
            if (key == null) continue;
            Object src = sources.get(key);
            if (src != null && SOURCE_USER_EXPLICIT.equals(String.valueOf(src))) return true;
        }
        return false;
    }

    static boolean isPlanLikeSource(Object src) {
        if (src == null) return false;
        String s = String.valueOf(src);
        return SOURCE_PLAN.equals(s) || SOURCE_LLM_INFERRED.equals(s) || SOURCE_USER_EXPLICIT.equals(s);
    }

    private static String resolveComponentParamSource(Component component, String key) {
        if (component == null || component.params() == null || key == null) {
            return SOURCE_LLM_INFERRED;
        }
        return resolveComponentParamSource(component.params(), key);
    }

    private static String resolveComponentParamSource(Map<String, Object> params, String key) {
        if (params == null || key == null) {
            return SOURCE_LLM_INFERRED;
        }
        // Only sealed server-verified stamps grant hard USER_EXPLICIT.
        if (UserConstraintContract.isServerVerified(params, key)) {
            return SOURCE_USER_EXPLICIT;
        }
        Object designSources = params.get("design_sources");
        if (designSources instanceof Map<?, ?> ds) {
            Object tagged = ds.get(key);
            if (tagged == null && ("height".equals(key) || "towerHeight".equals(key))) {
                tagged = firstNonNull(ds.get("height"), ds.get("towerHeight"));
            }
            if (tagged != null) {
                String t = String.valueOf(tagged).trim().toUpperCase();
                // LLM-claimed USER_EXPLICIT is demoted — forged hard constraints caused E_PAGODA_SIZE.
                if ("USER_EXPLICIT".equals(t) || "USER".equals(t) || "PLAYER".equals(t)
                        || "LLM_INFERRED".equals(t) || "AI_PLANNED".equals(t) || "PLAN".equals(t)) {
                    return SOURCE_LLM_INFERRED;
                }
            }
        }
        // Booleans on the component are also LLM-controllable — treat as inferred unless verified.
        if (("height".equals(key) || "towerHeight".equals(key))
                && (truthy(params.get("height_explicit"))
                || truthy(params.get("user_explicit_height"))
                || truthy(params.get("height_user_explicit")))) {
            return SOURCE_LLM_INFERRED;
        }
        Object nested = params.get("_param_sources");
        if (nested instanceof Map<?, ?> existing && existing.get(key) != null) {
            String s = String.valueOf(existing.get(key));
            // Nested USER_EXPLICIT from plan JSON is untrusted without server verification.
            if (isPlanLikeSource(s) || SOURCE_USER_EXPLICIT.equals(s)) return SOURCE_LLM_INFERRED;
        }
        return SOURCE_LLM_INFERRED;
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
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
            if (isPlanLikeSource(sources.get(key))) continue;
            if (!snapshot.containsKey(key) || !Objects.equals(snapshot.get(key), e.getValue())) {
                sources.put(key, SOURCE_LANDMARK_PRIOR);
            }
        }

        if (spec != null) {
            if (spec.getExtra() != null) {
                for (Map.Entry<String, Object> e : spec.getExtra().entrySet()) {
                    if (e.getKey() == null || PARAM_SOURCES_KEY.equals(e.getKey())) continue;
                    merged.put(e.getKey(), e.getValue());
                    sources.put(e.getKey(), SOURCE_LLM_INFERRED);
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
                sources.put("height", SOURCE_LLM_INFERRED);
                if (!merged.containsKey("towerHeight")) {
                    merged.put("towerHeight", spec.getHeight());
                    sources.put("towerHeight", SOURCE_LLM_INFERRED);
                }
            }
            if (spec.getFootprint() != null) {
                int w = spec.getFootprint().getWidth();
                int d = spec.getFootprint().getDepth();
                if (w > 0) {
                    merged.put("width", w);
                    sources.put("width", SOURCE_LLM_INFERRED);
                }
                if (d > 0) {
                    merged.put("depth", d);
                    sources.put("depth", SOURCE_LLM_INFERRED);
                }
                if (Math.max(w, d) > 0) {
                    merged.put("baseWidth", Math.max(w, d));
                    sources.put("baseWidth", SOURCE_LLM_INFERRED);
                }
            }
            if (spec.getFloors() > 0) {
                merged.put("levels", spec.getFloors());
                sources.put("levels", SOURCE_LLM_INFERRED);
            }
        }
        normalizeAliases(merged, sources);
        merged.put("typology_id", typologyId);
        merged.put("structural_typology", typologyId);
        merged.put(PARAM_SOURCES_KEY, Map.copyOf(sources));
        return merged;
    }

    /**
     * Promote common LLM snake_case keys to the camelCase / canonical names builders read.
     * Shape/size alts overwrite registry/prior defaults (LLM intent); other alts fill if absent.
     * <p>
     * Value promotion and source promotion run together so {@code towerHeight=28 (PLAN)}
     * cannot leave {@code height=47 (TYPOLOGY_DEFAULT)} while marking height as PLAN.
     */
    static void normalizeAliases(Map<String, Object> merged) {
        normalizeAliases(merged, null);
    }

    static void normalizeAliases(Map<String, Object> merged, Map<String, String> sources) {
        if (merged == null || merged.isEmpty()) return;
        promote(merged, sources, "footprint", "footprint_shape", "footprintShape");
        // floor_count = shaft storeys; do NOT fold eave_tiers into floors (重檐 ≠ 更多层).
        promote(merged, sources, "floor_count", "floorCount", "story_count", "storyCount");
        alias(merged, sources, "floor_count", "floors", "levels");
        if (merged.containsKey("floor_count")) {
            merged.put("levels", merged.get("floor_count"));
            if (sources != null && sources.containsKey("floor_count")) {
                sources.put("levels", sources.get("floor_count"));
            }
        }
        // height ↔ towerHeight: PLAN alias overwrites soft default on the canonical key.
        syncHeightAliases(merged, sources);
        promote(merged, sources, "eaves_per_level", "eavesPerLevel");
        alias(merged, sources, "eave_tiers", "eaveTiers");
        promote(merged, sources, "baseWidth", "base_width", "basewidth");
        promote(merged, sources, "baseDepth", "base_depth");
        promote(merged, sources, "baseRadius", "base_radius");
        promote(merged, sources, "hallRadius", "hall_radius");
        alias(merged, sources, "detailLevel", "detail_level");
        // Plan/LLM wall_block and roof_block must overwrite typology registry defaults
        // (alias() skips when bodyBlock/eaveBlock already exist from defaults).
        promote(merged, sources, "bodyBlock", "body_block", "wall_block", "wallBlock");
        promote(merged, sources, "eaveBlock", "eave_block", "roof_block", "roofBlock");
        alias(merged, sources, "accentBlock", "accent_block");
        alias(merged, sources, "trimBlock", "trim_block");
        alias(merged, sources, "reference_landmark", "referenceLandmark", "referenceLandmarkId", "landmark");
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
        if (sources != null) {
            for (String key : merged.keySet()) {
                if (PARAM_SOURCES_KEY.equals(key) || sources.containsKey(key)) continue;
                sources.put(key, SOURCE_TYPOLOGY_DEFAULT);
            }
        }
    }

    /**
     * Prefer plan-authored height aliases over soft registry defaults.
     * When both height and towerHeight are authored with different values, keep {@code height}
     * and mirror it onto {@code towerHeight} (canonical wins on conflict).
     * USER_EXPLICIT source is preserved when present.
     */
    private static void syncHeightAliases(Map<String, Object> merged, Map<String, String> sources) {
        if (merged == null) return;
        String heightSrc = sources == null ? null : sources.get("height");
        String towerSrc = sources == null ? null : sources.get("towerHeight");
        boolean heightAuthored = isPlanLikeSource(heightSrc);
        boolean towerAuthored = isPlanLikeSource(towerSrc);
        Object heightVal = merged.get("height");
        Object towerVal = merged.get("towerHeight");

        if (towerAuthored && !heightAuthored && towerVal != null) {
            merged.put("height", towerVal);
            if (sources != null) sources.put("height", towerSrc);
            return;
        }
        if (heightAuthored && !towerAuthored && heightVal != null) {
            merged.put("towerHeight", heightVal);
            if (sources != null) sources.put("towerHeight", heightSrc);
            return;
        }
        if (heightAuthored && towerAuthored && heightVal != null && towerVal != null
                && !Objects.equals(String.valueOf(heightVal), String.valueOf(towerVal))) {
            // Prefer USER_EXPLICIT side; otherwise canonical height wins.
            if (SOURCE_USER_EXPLICIT.equals(towerSrc) && !SOURCE_USER_EXPLICIT.equals(heightSrc)) {
                merged.put("height", towerVal);
                if (sources != null) {
                    sources.put("height", SOURCE_USER_EXPLICIT);
                    sources.put("height_alias_conflict", "towerHeight_user_wins");
                }
            } else {
                merged.put("towerHeight", heightVal);
                if (sources != null) {
                    sources.put("towerHeight", heightSrc != null ? heightSrc : SOURCE_LLM_INFERRED);
                    sources.put("height_alias_conflict", "height_wins");
                }
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
        promote(merged, null, canonical, alts);
    }

    private static void promote(
            Map<String, Object> merged, Map<String, String> sources, String canonical, String... alts
    ) {
        for (String alt : alts) {
            if (!merged.containsKey(alt)) continue;
            Object v = merged.get(alt);
            if (v != null && !String.valueOf(v).isBlank()) {
                boolean altPlan = sources != null && isPlanLikeSource(sources.get(alt));
                boolean canonPlan = sources != null && isPlanLikeSource(sources.get(canonical));
                // Soft default on canonical must yield to plan-authored alias.
                if (merged.containsKey(canonical) && !altPlan && canonPlan) {
                    return;
                }
                if (merged.containsKey(canonical) && !altPlan
                        && sources != null && SOURCE_TYPOLOGY_DEFAULT.equals(sources.get(canonical))
                        && !isPlanLikeSource(sources.get(alt))
                        && !SOURCE_LANDMARK_PRIOR.equals(sources.get(alt))) {
                    // Both soft — keep first canonical.
                    return;
                }
                if (altPlan || !merged.containsKey(canonical)
                        || (sources != null && !isPlanLikeSource(sources.get(canonical)))) {
                    merged.put(canonical, v);
                    if (sources != null) {
                        String src = sources.get(alt);
                        if (src != null) sources.put(canonical, src);
                    }
                }
                return;
            }
        }
    }

    private static void alias(Map<String, Object> merged, String canonical, String... alts) {
        alias(merged, null, canonical, alts);
    }

    private static void alias(
            Map<String, Object> merged, Map<String, String> sources, String canonical, String... alts
    ) {
        if (merged.containsKey(canonical)) {
            // Soft registry value must still yield to a plan-authored alias.
            if (sources != null && !isPlanLikeSource(sources.get(canonical))) {
                for (String alt : alts) {
                    if (!isPlanLikeSource(sources.get(alt))) continue;
                    Object v = merged.get(alt);
                    if (v != null && !String.valueOf(v).isBlank()) {
                        merged.put(canonical, v);
                        sources.put(canonical, sources.get(alt));
                        return;
                    }
                }
            }
            return;
        }
        for (String alt : alts) {
            if (merged.containsKey(alt)) {
                Object v = merged.get(alt);
                if (v != null && !String.valueOf(v).isBlank()) {
                    merged.put(canonical, v);
                    if (sources != null) {
                        String src = sources.get(alt);
                        if (src != null) sources.put(canonical, src);
                    }
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
