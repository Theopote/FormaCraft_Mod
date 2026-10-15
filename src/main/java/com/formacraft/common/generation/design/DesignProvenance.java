package com.formacraft.common.generation.design;

import com.formacraft.common.llm.dto.LlmPlan;

import java.util.HashMap;
import java.util.Map;

public final class DesignProvenance {
    private DesignProvenance() {}

    public static boolean canMutateParam(LlmPlan plan, String paramKey) {
        return canMutateParam(plan, null, paramKey);
    }

    /**
     * Plan-level {@code design_sources} and optional component {@code params.design_sources}
     * both lock USER_EXPLICIT / AI_PLANNED keys.
     */
    @SuppressWarnings("unchecked")
    public static boolean canMutateParam(LlmPlan plan, Map<String, Object> componentParams, String paramKey) {
        if (paramKey == null) {
            return true;
        }
        if (plan != null && plan.designSources() != null) {
            DesignParamSource source = DesignParamSource.parse(plan.designSources().get(paramKey));
            if (source != null && source.isDesignLocked()) {
                return false;
            }
        }
        if (componentParams != null) {
            Object nested = componentParams.get("design_sources");
            if (nested instanceof Map<?, ?> map) {
                Object raw = map.get(paramKey);
                DesignParamSource source = DesignParamSource.parse(raw == null ? null : String.valueOf(raw));
                if (source != null && source.isDesignLocked()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Writes {@code value} only when plan/component provenance allows changing {@code paramKey}. */
    public static boolean putIfAllowed(LlmPlan plan, Map<String, Object> params, String paramKey, Object value) {
        if (params == null || paramKey == null || !canMutateParam(plan, params, paramKey)) {
            return false;
        }
        params.put(paramKey, value);
        return true;
    }

    /** Removes {@code paramKey} (and optional aliases) only when provenance allows mutation. */
    public static boolean removeIfAllowed(LlmPlan plan, Map<String, Object> params, String... paramKeys) {
        if (params == null || paramKeys == null || paramKeys.length == 0) {
            return false;
        }
        String primary = paramKeys[0];
        if (!canMutateParam(plan, params, primary)) {
            return false;
        }
        boolean removed = false;
        for (String key : paramKeys) {
            if (key != null && params.remove(key) != null) {
                removed = true;
            }
        }
        return removed;
    }

    /** True when features / decorative_elements may be auto-appended by bridges. */
    public static boolean canEnrichFeatures(LlmPlan plan, Map<String, Object> componentParams) {
        return canMutateParam(plan, componentParams, "features")
                && canMutateParam(plan, componentParams, "distinctive_features")
                && canMutateParam(plan, componentParams, "decorative_elements");
    }

    public static Map<String, String> tagCompilerInferred(String... paramKeys) {
        Map<String, String> out = new HashMap<>();
        if (paramKeys != null) {
            for (String key : paramKeys) {
                if (key != null && !key.isBlank()) {
                    out.put(key, DesignParamSource.COMPILER_INFERRED.name());
                }
            }
        }
        return out;
    }

    public static Map<String, Object> markInferredParams(Map<String, Object> params) {
        if (params == null) {
            params = new HashMap<>();
        }
        params.putIfAbsent("_compiler_inferred", Boolean.TRUE);
        return params;
    }
}
