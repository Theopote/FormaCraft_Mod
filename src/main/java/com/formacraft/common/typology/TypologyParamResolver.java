package com.formacraft.common.typology;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.model.build.BuildingSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/** Merge typology registry defaults with component/spec params — no landmark hard-routing. */
public final class TypologyParamResolver {

    private TypologyParamResolver() {}

    public static Map<String, Object> merge(String typologyId, Component component) {
        Map<String, Object> merged = baseDefaults(typologyId);
        if (component != null && component.params() != null) {
            merged.putAll(component.params());
        }
        normalizeAliases(merged);
        merged.put("typology_id", typologyId);
        merged.put("structural_typology", typologyId);
        String ref = component != null ? TypologyComponentRouter.extractReferenceLandmark(component) : null;
        if (ref != null && !ref.isBlank()) {
            merged.putIfAbsent("reference_landmark", ref);
        }
        TypologyReferencePresets.apply(merged);
        return merged;
    }

    public static Map<String, Object> fromBuildingSpec(BuildingSpec spec, String typologyId) {
        Map<String, Object> merged = baseDefaults(typologyId);
        if (spec != null) {
            if (spec.getExtra() != null) {
                merged.putAll(spec.getExtra());
            }
            if ("tailiang_timber_hall".equals(typologyId) && spec.getMaterials()!=null) {
                if(spec.getMaterials().getWall()!=null) merged.putIfAbsent("wall_block",spec.getMaterials().getWall());
                if(spec.getMaterials().getFloor()!=null) merged.putIfAbsent("floor_block",spec.getMaterials().getFloor());
                if(spec.getMaterials().getRoof()!=null) merged.putIfAbsent("roof_block",spec.getMaterials().getRoof());
                if(spec.getMaterials().getWindow()!=null) merged.putIfAbsent("window_block",spec.getMaterials().getWindow());
                if(spec.getMaterials().getFoundation()!=null) merged.putIfAbsent("foundation_block",spec.getMaterials().getFoundation());
            }
            if (spec.getHeight() > 0) {
                merged.putIfAbsent("height", spec.getHeight());
                merged.putIfAbsent("towerHeight", spec.getHeight());
            }
            if (spec.getFootprint() != null) {
                int w = spec.getFootprint().getWidth();
                int d = spec.getFootprint().getDepth();
                if (w > 0) merged.putIfAbsent("width", w);
                if (d > 0) merged.putIfAbsent("depth", d);
                merged.putIfAbsent("baseWidth", Math.max(w, d));
            }
            if (spec.getFloors() > 0) {
                merged.putIfAbsent("levels", spec.getFloors());
            }
            if (spec.getExtra() != null) {
                Object lm = spec.getExtra().get("landmark");
                if (lm != null) {
                    merged.putIfAbsent("reference_landmark", String.valueOf(lm).trim());
                }
            }
        }
        normalizeAliases(merged);
        merged.put("typology_id", typologyId);
        merged.put("structural_typology", typologyId);
        TypologyReferencePresets.apply(merged);
        return merged;
    }

    /**
     * Promote common LLM snake_case keys to the camelCase / canonical names builders read.
     * Shape/size alts overwrite registry defaults (LLM intent); other alts fill only if absent.
     */
    static void normalizeAliases(Map<String, Object> merged) {
        if (merged == null || merged.isEmpty()) return;
        // LLM footprint_shape / story_count must beat registry defaults like footprint=octagon.
        promote(merged, "footprint", "footprint_shape", "footprintShape");
        promote(merged, "levels", "story_count", "storyCount");
        promote(merged, "baseWidth", "base_width", "basewidth");
        promote(merged, "baseDepth", "base_depth");
        promote(merged, "baseRadius", "base_radius");
        promote(merged, "hallRadius", "hall_radius");
        alias(merged, "levels", "floors");
        alias(merged, "detailLevel", "detail_level");
        alias(merged, "bodyBlock", "body_block", "wall_block", "wallBlock");
        alias(merged, "eaveBlock", "eave_block");
        alias(merged, "accentBlock", "accent_block");
        alias(merged, "trimBlock", "trim_block");
        alias(merged, "reference_landmark", "referenceLandmark", "referenceLandmarkId", "landmark");
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
