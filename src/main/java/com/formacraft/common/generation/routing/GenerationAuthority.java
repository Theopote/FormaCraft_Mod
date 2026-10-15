package com.formacraft.common.generation.routing;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.Locale;
import java.util.Map;

/**
 * Explicit generation authority for a component (or plan default).
 * When unset, defaults to {@link #COMPONENTS} — registry geometry must not be
 * silently replaced by typology.
 */
public enum GenerationAuthority {
    COMPONENTS,
    TYPOLOGY,
    ASSEMBLY,
    CAPTURED_LIBRARY,
    SKELETON;

    public static final String PARAM_KEY = "generation_authority";
    public static final String META_KEY = PARAM_KEY;

    public static GenerationAuthority parse(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return GenerationAuthority.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Resolve order: component params → plan_meta → default {@link #COMPONENTS}.
     */
    public static GenerationAuthority resolve(Component component, Map<String, Object> planMeta) {
        if (component != null && component.params() != null) {
            GenerationAuthority fromComponent = parse(component.params().get(PARAM_KEY));
            if (fromComponent != null) {
                return fromComponent;
            }
        }
        if (planMeta != null) {
            GenerationAuthority fromPlan = parse(planMeta.get(META_KEY));
            if (fromPlan != null) {
                return fromPlan;
            }
        }
        return COMPONENTS;
    }

    public static GenerationAuthority resolve(Component component, LlmPlan plan) {
        return resolve(component, plan == null ? null : plan.planMeta());
    }

    /** True when typology may replace non-empty registry/expander geometry. */
    public boolean allowsTypologyReplace(boolean allowTypologyOverride) {
        return GenerationFallbackPolicy.forAuthority(this).allowsTypologyReplace(allowTypologyOverride);
    }

    /** @deprecated use {@link GenerationFallbackPolicy#allowTypologyWhenBaseEmpty()} */
    @Deprecated
    public boolean allowsEmptyBaseFallback() {
        GenerationFallbackPolicy policy = GenerationFallbackPolicy.forAuthority(this);
        return policy.allowTypologyWhenBaseEmpty() || policy.allowStructureWhenBaseEmpty();
    }

    public GenerationFallbackPolicy fallbackPolicy() {
        return GenerationFallbackPolicy.forAuthority(this);
    }
}
