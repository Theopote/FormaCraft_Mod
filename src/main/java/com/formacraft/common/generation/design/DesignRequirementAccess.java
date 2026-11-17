package com.formacraft.common.generation.design;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.style.ExplicitDesignPolicy;
import com.formacraft.common.typology.UserConstraintContract;

import java.util.List;
import java.util.Locale;
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

    /**
     * Priority (trusted provenance only elevates hard constraints):
     * <ol>
     *   <li>Server-verified local mass requirement (REQUIRED / OPTIONAL / OMITTED)</li>
     *   <li>Plan-level {@code design_requirements}</li>
     *   <li>Untrusted AI mass requirement (no plan conflict)</li>
     *   <li>AI {@code none} / disabled param when no plan requirement → local OMITTED</li>
     * </ol>
     * Bare {@code design_sources=USER_EXPLICIT} from LLM JSON is ignored until
     * {@link UserConstraintContract#stampVerified} seals the key.
     */
    public static DesignRequirement facade(LlmPlan plan, Component mass) {
        return resolveOmitAware(
                plan,
                mass,
                FACADE,
                ExplicitDesignPolicy.windowsDisabled(mass),
                new String[] {"window_style", "windowStyle", "window_ratio"},
                "facade_requirement", "window_requirement", "windows_requirement");
    }

    public static DesignRequirement entrance(LlmPlan plan, Component mass) {
        return resolveOmitAware(
                plan,
                mass,
                ENTRANCE,
                ExplicitDesignPolicy.entranceDisabled(mass),
                new String[] {"entrance_type", "entranceType"},
                "entrance_requirement", "door_requirement");
    }

    public static DesignRequirement roof(LlmPlan plan, Component mass) {
        return resolveOmitAware(
                plan,
                mass,
                ROOF,
                ExplicitDesignPolicy.roofDisabled(mass),
                new String[] {"roof_type", "roofType"},
                "roof_requirement");
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

    /**
     * Resolve omit/required with unified provenance × value priority.
     * Only {@link UserConstraintContract}-sealed keys beat plan-level requirements.
     */
    private static DesignRequirement resolveOmitAware(
            LlmPlan plan,
            Component mass,
            String planKey,
            boolean disabledByParam,
            String[] omitParamKeys,
            String... massRequirementKeys
    ) {
        DesignRequirement massReq = fromMassParam(mass, massRequirementKeys);
        DesignRequirement planReq = fromPlanMeta(plan, planKey);
        boolean trustedMassRequirement = isTrustedUserExplicit(mass, massRequirementKeys);
        boolean trustedOmitParam = isTrustedUserExplicit(mass, omitParamKeys);

        // 1. Server-verified local mass requirement (any of REQUIRED / OPTIONAL / OMITTED).
        if (massReq != null && trustedMassRequirement) {
            return massReq;
        }
        // Trusted roof_type=none (etc.) without a separate requirement key.
        if (disabledByParam && trustedOmitParam) {
            return DesignRequirement.OMITTED;
        }

        // 2. Plan-level player / server design_requirements beat untrusted AI mass values.
        if (planReq != null) {
            return planReq;
        }

        // 3. Untrusted AI mass requirement when no plan conflict.
        if (massReq != null) {
            return massReq;
        }

        // 4. AI-authored none / disabled with no plan requirement → local omit.
        if (disabledByParam) {
            return DesignRequirement.OMITTED;
        }
        return DesignRequirement.UNSPECIFIED;
    }

    /** True when at least one key carries a server-minted USER_EXPLICIT seal. */
    static boolean isTrustedUserExplicit(Component mass, String... keys) {
        if (mass == null || mass.params() == null || keys == null) {
            return false;
        }
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            if (UserConstraintContract.isServerVerified(mass.params(), key)) {
                return true;
            }
            for (String alias : DesignParameterRegistry.aliases(key)) {
                if (alias != null && UserConstraintContract.isServerVerified(mass.params(), alias)) {
                    return true;
                }
            }
        }
        return false;
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
