package com.formacraft.common.generation.routing;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Compile-time guard for {@link GenerationAuthority} on plan_meta and component params. */
public final class GenerationAuthorityValidator {
    private GenerationAuthorityValidator() {}

    public static Optional<CapabilityGap> check(LlmPlan plan) {
        if (plan == null) {
            return Optional.empty();
        }
        Map<String, Object> planMeta = plan.planMeta();
        if (planMeta != null && planMeta.containsKey(GenerationAuthority.META_KEY)) {
            Optional<CapabilityGap> gap = validateRaw(
                    planMeta.get(GenerationAuthority.META_KEY), "plan_meta");
            if (gap.isPresent()) {
                return gap;
            }
        }
        if (plan.components() == null) {
            return Optional.empty();
        }
        int index = 0;
        for (Component component : plan.components()) {
            if (component != null && component.params() != null
                    && component.params().containsKey(GenerationAuthority.PARAM_KEY)) {
                Optional<CapabilityGap> gap = validateRaw(
                        component.params().get(GenerationAuthority.PARAM_KEY),
                        "components[" + index + "].params");
                if (gap.isPresent()) {
                    return gap;
                }
            }
            index++;
        }
        return Optional.empty();
    }

    private static Optional<CapabilityGap> validateRaw(Object raw, String path) {
        try {
            GenerationAuthority.validateRaw(raw, path);
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of(new CapabilityGap(
                    "E_INVALID_GENERATION_AUTHORITY",
                    e.getMessage(),
                    path,
                    List.of("Use COMPONENTS, TYPOLOGY, ASSEMBLY, CAPTURED_LIBRARY, or SKELETON.")));
        }
    }
}
