package com.formacraft.server.compiler;

import com.formacraft.common.generation.routing.GenerationAuthorityValidator;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy;

import java.util.List;
import java.util.Optional;

/**
 * World-independent design gates. Returns the first failure without touching
 * AssemblyCompileDiagnostics; the compiler owns publication and short-circuiting.
 * Authored and prepared passes intentionally retain different material and
 * authority checks, and must run on their respective component snapshots.
 */
final class DesignValidatorSuite {
    private DesignValidatorSuite() {}

    /** Run after plan normalization, before inference can repair or replace author intent. */
    static Optional<CapabilityGap> checkAuthored(LlmPlan plan) {
        List<Component> components = plan.components() == null ? List.of() : plan.components();
        var design = checkDesign(plan, components);
        if (design.isPresent()) return design;
        return ExplicitMaterialPolicy.invalidAttribute(plan.styleAttributes(), plan.components())
                .map(invalid -> new CapabilityGap("E_MATERIAL_INVALID",
                        "无法解析明确指定的材料：" + invalid, "style_attributes",
                        List.of("Use a registered block id or a supported material name.")));
    }

    /** Validate inferred components; authority still comes from the original normalized plan. */
    static Optional<CapabilityGap> checkPrepared(LlmPlan plan, List<Component> components) {
        var authority = GenerationAuthorityValidator.check(plan);
        if (authority.isPresent()) return authority;
        var design = checkDesign(plan, components);
        if (design.isPresent()) return design;
        for (var component : components) {
            if (component == null) continue;
            var invalid = ExplicitMaterialPolicy.invalidComponent(component);
            if (invalid.isPresent()) {
                return Optional.of(new CapabilityGap("E_MATERIAL_INVALID",
                        "无法解析明确指定的构件材料：" + invalid.get(), "components[]",
                        List.of("Use a registered block id and valid state properties.")));
            }
        }
        return Optional.empty();
    }

    /** Order is observable: repairs and previews use the first diagnostic. Keep checks lazy. */
    private static Optional<CapabilityGap> checkDesign(LlmPlan plan, List<Component> components) {
        return RoofDesignValidator.check(plan, components)
                .or(() -> FacadeDesignValidator.check(plan, components))
                .or(() -> MassDesignValidator.check(plan, components))
                .or(() -> HostedPlateDesignValidator.check(plan, components))
                .or(() -> ExplicitDesignConflictValidator.check(plan, components));
    }
}
