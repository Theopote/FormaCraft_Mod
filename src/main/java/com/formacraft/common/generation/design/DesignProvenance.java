package com.formacraft.common.generation.design;

import com.formacraft.common.llm.dto.LlmPlan;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class DesignProvenance {
    private DesignProvenance() {}

    /**
     * Plan-level {@code design_sources} and component {@code params.design_sources};
     * any alias of the same canonical param shares one lock group.
     */
    public static boolean canMutateParam(LlmPlan plan, Map<String, Object> componentParams, String paramKey) {
        if (paramKey == null) {
            return true;
        }
        String canonical = DesignParameterRegistry.canonicalKey(paramKey);
        for (String alias : DesignParameterRegistry.aliases(canonical)) {
            if (!canMutateParamByExactKey(plan, componentParams, alias)) {
                return false;
            }
        }
        return true;
    }

    /** Single-key provenance check (no alias expansion). */
    public static boolean canMutateParamByExactKey(
            LlmPlan plan,
            Map<String, Object> componentParams,
            String paramKey
    ) {
        if (paramKey == null) {
            return true;
        }
        if (plan != null && plan.designSources() != null) {
            DesignParamSource source = DesignParamSource.parse(plan.designSources().get(paramKey));
            if (source != null && source.isDesignLocked()) {
                return false;
            }
            String canonical = DesignParameterRegistry.canonicalKey(paramKey);
            if (!canonical.equals(paramKey)) {
                source = DesignParamSource.parse(plan.designSources().get(canonical));
                if (source != null && source.isDesignLocked()) {
                    return false;
                }
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

    /** Writes {@code value} only when plan/component provenance allows changing the param group. */
    public static boolean putIfAllowed(LlmPlan plan, Map<String, Object> params, String paramKey, Object value) {
        if (params == null || paramKey == null || !canMutateParam(plan, params, paramKey)) {
            return false;
        }
        String canonical = DesignParameterRegistry.canonicalKey(paramKey);
        String writeKey = DesignParameterRegistry.lockedHolderKey(params, canonical);
        params.put(writeKey, value);
        for (String alias : DesignParameterRegistry.aliases(canonical)) {
            if (!alias.equals(writeKey) && canMutateParamByExactKey(plan, params, alias)) {
                params.remove(alias);
            }
        }
        return true;
    }

    /**
     * Removes each key independently when provenance allows mutation of that key.
     * Unlike {@link #putIfAllowed}, alias lock groups are not shared — a locked
     * {@code crownTemplate} must not block removal of unlocked {@code crown_template}.
     */
    public static boolean removeIfAllowed(LlmPlan plan, Map<String, Object> params, String... paramKeys) {
        if (params == null || paramKeys == null || paramKeys.length == 0) {
            return false;
        }
        boolean removed = false;
        for (String key : paramKeys) {
            if (key != null && canMutateParamByExactKey(plan, params, key) && params.remove(key) != null) {
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
