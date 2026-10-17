package com.formacraft.common.generation.design;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.style.ExplicitDesignPolicy;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Resolves facade/entrance/roof/crown/circulation requirement level from plan and mass params. */
public final class DesignRequirementAccess {
    private DesignRequirementAccess() {}

    public static final String FACADE = "facade";
    public static final String ENTRANCE = "entrance";
    public static final String ROOF = "roof";
    public static final String CROWN = "crown";
    public static final String CIRCULATION = "circulation";

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

    /**
     * Whole-building walkability after compile. Only {@link DesignRequirement#REQUIRED} enables
     * {@code BuildingCirculationValidator}; {@link DesignRequirement#UNSPECIFIED} does not fail.
     */
    public static DesignRequirement circulation(LlmPlan plan) {
        DesignRequirement explicit = fromPlanMeta(plan, CIRCULATION);
        return explicit == null ? DesignRequirement.UNSPECIFIED : explicit;
    }

    /**
     * Connectivity scope when circulation is REQUIRED.
     * Key absent → {@link CirculationScope#BUILDING_LOCAL}. Key present must be valid
     * (call {@link #validateCirculationScope} before trusting this under REQUIRED).
     */
    public static CirculationScope circulationScope(LlmPlan plan) {
        if (plan == null || plan.planMeta() == null) {
            return CirculationScope.BUILDING_LOCAL;
        }
        if (!plan.planMeta().containsKey(CirculationScope.META_KEY)) {
            return CirculationScope.BUILDING_LOCAL;
        }
        Object raw = plan.planMeta().get(CirculationScope.META_KEY);
        CirculationScope parsed = CirculationScope.parse(raw == null ? null : String.valueOf(raw));
        return parsed == null ? CirculationScope.BUILDING_LOCAL : parsed;
    }

    /**
     * When {@code plan_meta.circulation_scope} is present but not a known enum value,
     * returns {@code E_CIRCULATION_SCOPE_INVALID} (no silent downgrade).
     */
    public static Optional<CapabilityGap> validateCirculationScope(LlmPlan plan) {
        if (plan == null || plan.planMeta() == null
                || !plan.planMeta().containsKey(CirculationScope.META_KEY)) {
            return Optional.empty();
        }
        Object raw = plan.planMeta().get(CirculationScope.META_KEY);
        String text = raw == null ? "" : String.valueOf(raw).trim();
        if (CirculationScope.parse(text) != null) {
            return Optional.empty();
        }
        return Optional.of(new CapabilityGap(
                "E_CIRCULATION_SCOPE_INVALID",
                "Unknown circulation_scope='" + text + "' (expected BUILDING_LOCAL or CONNECTED_COMPLEX)",
                "plan_meta.circulation_scope",
                List.of("Use BUILDING_LOCAL or CONNECTED_COMPLEX.",
                        "Omit circulation_scope to default to BUILDING_LOCAL.")));
    }

    /**
     * Attached host-link proof mode. Key absent → {@link CirculationHostLinks#ROOT}.
     * Call {@link #validateCirculationHostLinks} under REQUIRED before trusting a present key.
     */
    public static CirculationHostLinks circulationHostLinks(LlmPlan plan) {
        if (plan == null || plan.planMeta() == null
                || !plan.planMeta().containsKey(CirculationHostLinks.META_KEY)) {
            return CirculationHostLinks.ROOT;
        }
        Object raw = plan.planMeta().get(CirculationHostLinks.META_KEY);
        CirculationHostLinks parsed = CirculationHostLinks.parse(raw == null ? null : String.valueOf(raw));
        return parsed == null ? CirculationHostLinks.ROOT : parsed;
    }

    /**
     * When {@code plan_meta.circulation_host_links} is present but not a known enum value,
     * returns {@code E_CIRCULATION_HOST_LINKS_INVALID}.
     */
    public static Optional<CapabilityGap> validateCirculationHostLinks(LlmPlan plan) {
        if (plan == null || plan.planMeta() == null
                || !plan.planMeta().containsKey(CirculationHostLinks.META_KEY)) {
            return Optional.empty();
        }
        Object raw = plan.planMeta().get(CirculationHostLinks.META_KEY);
        String text = raw == null ? "" : String.valueOf(raw).trim();
        if (CirculationHostLinks.parse(text) != null) {
            return Optional.empty();
        }
        return Optional.of(new CapabilityGap(
                "E_CIRCULATION_HOST_LINKS_INVALID",
                "Unknown circulation_host_links='" + text + "' (expected ROOT or DIRECT)",
                "plan_meta.circulation_host_links",
                List.of("Use ROOT (default) or DIRECT.",
                        "Omit circulation_host_links to default to ROOT.")));
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
