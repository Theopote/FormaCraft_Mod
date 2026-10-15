package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.Optional;

/**
 * Extends explicit design conflict checks with compiler satellite policy (Phase 3–5).
 */
public final class DesignFidelityValidator {
    private DesignFidelityValidator() {}

    public static Optional<com.formacraft.common.llm.dto.CapabilityGap> checkMassSatellites(
            LlmPlan plan,
            Component mass,
            boolean hasFacade,
            boolean hasEntrance,
            boolean hasRoof,
            boolean useAssemblyFacade,
            boolean hasCrown,
            boolean wouldInferCrown
    ) {
        return CompilerSatellitePolicy.missingSatellitesGap(
                plan, mass, hasFacade, hasEntrance, hasRoof, useAssemblyFacade, hasCrown, wouldInferCrown);
    }
}
