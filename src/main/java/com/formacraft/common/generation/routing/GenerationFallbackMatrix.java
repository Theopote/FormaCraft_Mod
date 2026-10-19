package com.formacraft.common.generation.routing;

import com.formacraft.common.llm.dto.Component;

import java.util.Map;

/**
 * Authority × fallback permission matrix.
 * Explicit {@code allow_*_empty_fallback} flags apply only under {@link GenerationAuthority#COMPONENTS}.
 */
public final class GenerationFallbackMatrix {
    private GenerationFallbackMatrix() {}

    public static boolean effectiveTypologyEmpty(
            GenerationAuthority authority,
            GenerationFallbackPolicy policy,
            Component component,
            Map<String, Object> planMeta
    ) {
        if (authority == null) {
            authority = GenerationAuthority.COMPONENTS;
        }
        if (policy == null) {
            policy = GenerationFallbackPolicy.forAuthority(authority);
        }
        if (policy.allowTypologyWhenBaseEmpty()) {
            return true;
        }
        return authority == GenerationAuthority.COMPONENTS
                && PlanMetaAccess.allowTypologyEmptyFallback(component, planMeta);
    }

    public static boolean effectiveStructureEmpty(
            GenerationAuthority authority,
            GenerationFallbackPolicy policy,
            Component component,
            Map<String, Object> planMeta
    ) {
        // MODULE + landmark is an explicit executor request, not a generic
        // empty-house fallback. Explicit authority locks still take precedence.
        if ((authority == null || authority == GenerationAuthority.COMPONENTS)
                && component != null && "MODULE".equalsIgnoreCase(component.componentType())
                && com.formacraft.common.typology.TypologyComponentRouter.extractLandmarkModuleId(component) != null
                && (component.params() == null || GenerationAuthority.parse(component.params().get(GenerationAuthority.PARAM_KEY)) == null)
                && (planMeta == null || GenerationAuthority.parse(planMeta.get(GenerationAuthority.META_KEY)) == null)) return true;
        if (authority == null) {
            authority = GenerationAuthority.COMPONENTS;
        }
        if (policy == null) {
            policy = GenerationFallbackPolicy.forAuthority(authority);
        }
        if (policy.allowStructureWhenBaseEmpty()) {
            return true;
        }
        return authority == GenerationAuthority.COMPONENTS
                && PlanMetaAccess.allowStructureEmptyFallback(component, planMeta);
    }
}
