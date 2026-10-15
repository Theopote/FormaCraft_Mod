package com.formacraft.common.generation.design;

import com.formacraft.common.llm.dto.LlmPlan;

import java.util.HashMap;
import java.util.Map;

public final class DesignProvenance {
    private DesignProvenance() {}

    public static boolean canMutateParam(LlmPlan plan, String paramKey) {
        if (paramKey == null || plan == null || plan.designSources() == null) {
            return true;
        }
        DesignParamSource source = DesignParamSource.parse(plan.designSources().get(paramKey));
        return source == null || !source.isDesignLocked();
    }

    /** Writes {@code value} only when plan provenance allows changing {@code paramKey}. */
    public static boolean putIfAllowed(LlmPlan plan, Map<String, Object> params, String paramKey, Object value) {
        if (params == null || paramKey == null || !canMutateParam(plan, paramKey)) {
            return false;
        }
        params.put(paramKey, value);
        return true;
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
