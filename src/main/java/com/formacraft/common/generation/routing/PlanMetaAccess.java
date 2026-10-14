package com.formacraft.common.generation.routing;

import com.formacraft.common.llm.dto.LlmPlan;

import java.util.Map;

public final class PlanMetaAccess {
    private PlanMetaAccess() {}

    public static final String ALLOW_COMPILER_SATELLITES = "allow_compiler_satellites";

    public static boolean allowCompilerSatellites(LlmPlan plan) {
        if (plan == null) {
            return false;
        }
        return booleanMeta(plan.planMeta(), ALLOW_COMPILER_SATELLITES);
    }

    public static boolean booleanMeta(Map<String, Object> planMeta, String key) {
        if (planMeta == null || key == null) {
            return false;
        }
        return Boolean.TRUE.equals(planMeta.get(key));
    }
}
