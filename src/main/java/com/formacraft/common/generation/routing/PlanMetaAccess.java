package com.formacraft.common.generation.routing;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.Map;

public final class PlanMetaAccess {
    private PlanMetaAccess() {}

    public static final String ALLOW_COMPILER_SATELLITES = "allow_compiler_satellites";
    public static final String GENERATION_AUTHORITY = GenerationAuthority.PARAM_KEY;

    public static boolean allowCompilerSatellites(LlmPlan plan) {
        if (plan == null) {
            return false;
        }
        return booleanMeta(plan.planMeta(), ALLOW_COMPILER_SATELLITES);
    }

    /** Plan-level authority only (ignores component params). */
    public static GenerationAuthority planGenerationAuthority(LlmPlan plan) {
        if (plan == null) {
            return null;
        }
        return GenerationAuthority.parse(plan.planMeta() == null
                ? null
                : plan.planMeta().get(GENERATION_AUTHORITY));
    }

    /** Component params → plan_meta → {@link GenerationAuthority#COMPONENTS}. */
    public static GenerationAuthority generationAuthority(Component component, LlmPlan plan) {
        return GenerationAuthority.resolve(component, plan);
    }

    public static boolean booleanMeta(Map<String, Object> planMeta, String key) {
        if (planMeta == null || key == null) {
            return false;
        }
        return Boolean.TRUE.equals(planMeta.get(key));
    }
}
