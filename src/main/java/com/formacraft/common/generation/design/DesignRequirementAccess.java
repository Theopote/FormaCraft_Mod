package com.formacraft.common.generation.design;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.style.ExplicitDesignPolicy;

import java.util.Map;

/** Resolves facade/entrance/roof/crown requirement level from plan and mass params. */
public final class DesignRequirementAccess {
    private DesignRequirementAccess() {}

    public static final String FACADE = "facade";
    public static final String ENTRANCE = "entrance";
    public static final String ROOF = "roof";
    public static final String CROWN = "crown";

    public static DesignRequirement facade(LlmPlan plan, Component mass) {
        DesignRequirement explicit = fromPlanMeta(plan, FACADE);
        if (explicit != null) {
            return explicit;
        }
        explicit = fromMassParam(mass, "facade_requirement", "window_requirement", "windows_requirement");
        if (explicit != null) {
            return explicit;
        }
        if (ExplicitDesignPolicy.windowsDisabled(mass)) {
            return DesignRequirement.OMITTED;
        }
        return DesignRequirement.UNSPECIFIED;
    }

    public static DesignRequirement entrance(LlmPlan plan, Component mass) {
        DesignRequirement explicit = fromPlanMeta(plan, ENTRANCE);
        if (explicit != null) {
            return explicit;
        }
        explicit = fromMassParam(mass, "entrance_requirement", "door_requirement");
        if (explicit != null) {
            return explicit;
        }
        if (ExplicitDesignPolicy.entranceDisabled(mass)) {
            return DesignRequirement.OMITTED;
        }
        return DesignRequirement.UNSPECIFIED;
    }

    public static DesignRequirement roof(LlmPlan plan, Component mass) {
        DesignRequirement explicit = fromPlanMeta(plan, ROOF);
        if (explicit != null) {
            return explicit;
        }
        explicit = fromMassParam(mass, "roof_requirement");
        if (explicit != null) {
            return explicit;
        }
        if (ExplicitDesignPolicy.roofDisabled(mass)) {
            return DesignRequirement.OMITTED;
        }
        return DesignRequirement.UNSPECIFIED;
    }

    public static DesignRequirement crown(LlmPlan plan, Component mass, boolean wouldInferCrown) {
        DesignRequirement explicit = fromPlanMeta(plan, CROWN);
        if (explicit != null) {
            return explicit;
        }
        explicit = fromMassParam(mass, "crown_requirement");
        if (explicit != null) {
            return explicit;
        }
        if (!wouldInferCrown) {
            return DesignRequirement.OMITTED;
        }
        return DesignRequirement.UNSPECIFIED;
    }

    private static DesignRequirement fromPlanMeta(LlmPlan plan, String key) {
        if (plan == null || plan.planMeta() == null) {
            return null;
        }
        Object requirements = plan.planMeta().get("design_requirements");
        if (!(requirements instanceof Map<?, ?> map)) {
            return null;
        }
        Object raw = map.get(key);
        return raw == null ? null : DesignRequirement.parse(String.valueOf(raw));
    }

    private static DesignRequirement fromMassParam(Component mass, String... keys) {
        if (mass == null || mass.params() == null) {
            return null;
        }
        for (String key : keys) {
            Object raw = mass.params().get(key);
            if (raw != null) {
                DesignRequirement parsed = DesignRequirement.parse(String.valueOf(raw));
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
    }
}
