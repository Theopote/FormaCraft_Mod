package com.formacraft.server.compiler;

import com.formacraft.common.generation.design.DesignRequirement;
import com.formacraft.common.generation.design.DesignRequirementAccess;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Completeness checks for plans that explicitly disable compiler satellite completion. */
final class CompilerSatellitePolicy {
    private CompilerSatellitePolicy() {}

    static Optional<CapabilityGap> missingSatellitesGap(
            LlmPlan plan,
            Component mass,
            boolean hasFacade,
            boolean hasEntrance,
            boolean hasRoof,
            boolean useAssemblyFacade,
            boolean hasCrown,
            boolean wouldInferCrown
    ) {
        if (plan == null || plan.allowCompilerSatellites() || useAssemblyFacade) {
            return Optional.empty();
        }
        List<String> missing = new ArrayList<>();

        if (needsSatellite(DesignRequirementAccess.facade(plan, mass), hasFacade)) {
            missing.add("FACADE_WINDOWS");
        }
        if (needsSatellite(DesignRequirementAccess.entrance(plan, mass), hasEntrance)) {
            missing.add("ENTRANCE");
        }
        if (needsSatellite(DesignRequirementAccess.roof(plan, mass), hasRoof)) {
            missing.add("ROOF");
        }
        // A stylistic crown suggestion is optional; only an explicit requirement
        // may block an otherwise complete building in strict mode.
        if (DesignRequirementAccess.crown(plan, mass, wouldInferCrown) == DesignRequirement.REQUIRED && !hasCrown) {
            missing.add("CROWN");
        }

        if (missing.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CapabilityGap(
                "E_DESIGN_INCOMPLETE",
                "建筑计划缺少必要设计构件：" + String.join(", ", missing)
                        + "。请在计划中显式规划，设置 plan_meta.design_requirements，或 allow_compiler_satellites=true（过渡）。",
                "components[]",
                List.of(
                        "Add explicit components or mark elements OMITTED/OPTIONAL in plan_meta.design_requirements.",
                        "Or set plan_meta.allow_compiler_satellites=true during migration.")));
    }

    private static boolean needsSatellite(DesignRequirement requirement, boolean present) {
        if (present) {
            return false;
        }
        if (requirement == null || requirement == DesignRequirement.OMITTED || requirement == DesignRequirement.OPTIONAL) {
            return false;
        }
        return requirement.mustBePresentWhenStrict();
    }
}
