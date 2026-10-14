package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.style.ExplicitDesignPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** When LLM omits facade/entrance/roof satellites, fail closed unless transition flag is set. */
final class CompilerSatellitePolicy {
    private CompilerSatellitePolicy() {}

    static Optional<CapabilityGap> missingSatellitesGap(
            LlmPlan plan,
            Component mass,
            boolean hasFacade,
            boolean hasEntrance,
            boolean hasRoof,
            boolean useAssemblyFacade,
            boolean wouldInferCrown
    ) {
        if (plan == null || plan.allowCompilerSatellites() || useAssemblyFacade) {
            return Optional.empty();
        }
        List<String> missing = new ArrayList<>();
        if (!hasFacade && !ExplicitDesignPolicy.windowsDisabled(mass)) {
            missing.add("FACADE_WINDOWS");
        }
        if (!hasEntrance && !ExplicitDesignPolicy.entranceDisabled(mass)) {
            missing.add("ENTRANCE");
        }
        if (!hasRoof && !ExplicitDesignPolicy.roofDisabled(mass)) {
            missing.add("ROOF");
        }
        if (wouldInferCrown) {
            missing.add("CROWN");
        }
        if (missing.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CapabilityGap(
                "E_DESIGN_INCOMPLETE",
                "建筑计划缺少必要设计构件：" + String.join(", ", missing)
                        + "。请在计划中显式规划，或设置 plan_meta.allow_compiler_satellites=true（过渡）。",
                "components[]",
                List.of(
                        "Add explicit ROOF / FACADE_WINDOWS / ENTRANCE components for each MASS body.",
                        "Or set plan_meta.allow_compiler_satellites=true during migration.")));
    }
}
