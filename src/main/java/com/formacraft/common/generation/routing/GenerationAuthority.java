package com.formacraft.common.generation.routing;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.Locale;
import java.util.List;
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

    /** Strict parse for LLM plan validation; rejects unknown enum strings. */
    public static GenerationAuthority parseStrict(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return GenerationAuthority.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid generation_authority: " + s, e);
        }
    }

    /**
     * Validates a raw authority value at {@code path}. Null/blank means unset (valid).
     * Non-blank unknown values throw {@link IllegalArgumentException}.
     */
    public static void validateRaw(Object raw, String path) {
        if (raw == null) {
            return;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return;
        }
        try {
            parseStrict(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid generation_authority at " + path + ": " + s, e);
        }
    }

    /**
     * Resolve order: component params → component-type inference → plan_meta → default {@link #COMPONENTS}.
     * <p>
     * Inference: {@code component_type=ASSEMBLY} with an assembly payload is ASSEMBLY even when
     * plan_meta says COMPONENTS — otherwise ordinary freeform houses fail with
     * {@code generation_authority_conflict}. Explicit non-{@link #COMPONENTS} plan_meta still wins
     * over inference.
     */
    public static GenerationAuthority resolve(Component component, Map<String, Object> planMeta) {
        if (component != null && component.params() != null) {
            GenerationAuthority fromComponent = parse(component.params().get(PARAM_KEY));
            if (fromComponent != null) {
                return fromComponent;
            }
        }
        GenerationAuthority inferred = inferFromComponentShape(component);
        GenerationAuthority fromPlan = planMeta == null ? null : parse(planMeta.get(META_KEY));
        if (inferred != null) {
            // Generic COMPONENTS plan default must not override an ASSEMBLY-shaped component.
            if (fromPlan == null || fromPlan == COMPONENTS) {
                return inferred;
            }
            return fromPlan;
        }
        if (fromPlan != null) {
            return fromPlan;
        }
        return COMPONENTS;
    }

    /**
     * When authority is unset, align with the component's declared executor shape.
     */
    static GenerationAuthority inferFromComponentShape(Component component) {
        if (component == null || component.componentType() == null) {
            return null;
        }
        String type = component.componentType().trim().toUpperCase(Locale.ROOT);
        if ("ASSEMBLY".equals(type) && hasAssemblyPayload(component.params())) {
            return ASSEMBLY;
        }
        return null;
    }

    private static boolean hasAssemblyPayload(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return false;
        }
        Object nested = params.get("assembly");
        if (nested instanceof Map<?, ?> nestedMap && hasExecutableAssemblyKeys(nestedMap)) {
            return true;
        }
        return hasExecutableAssemblyKeys(params);
    }

    private static boolean hasExecutableAssemblyKeys(Map<?, ?> payload) {
        if (payload.get("preset") != null || payload.get("presetId") != null) {
            return true;
        }
        if (payload.get("ops") instanceof List<?> ops && !ops.isEmpty()) {
            return true;
        }
        if (payload.get("components") instanceof List<?> components && !components.isEmpty()) {
            return true;
        }
        return payload.get("graph") instanceof Map<?, ?> graph
                && graph.get("components") instanceof List<?> components
                && !components.isEmpty();
    }

    public static GenerationAuthority resolve(Component component, LlmPlan plan) {
        return resolve(component, plan == null ? null : plan.planMeta());
    }

    /** True when typology may replace non-empty registry/expander geometry. */
    public boolean allowsTypologyReplace(boolean allowTypologyOverride) {
        return GenerationFallbackPolicy.forAuthority(this).allowsTypologyReplace(allowTypologyOverride);
    }

    public GenerationFallbackPolicy fallbackPolicy() {
        return GenerationFallbackPolicy.forAuthority(this);
    }
}
