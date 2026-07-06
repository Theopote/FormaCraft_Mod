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
        merged.put("typology_id", typologyId);
        merged.put("structural_typology", typologyId);
        TypologyReferencePresets.apply(merged);
        return merged;
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
