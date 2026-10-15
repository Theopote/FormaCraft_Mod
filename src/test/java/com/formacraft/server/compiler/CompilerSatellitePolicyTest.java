package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilerSatellitePolicyTest {

    @Test
    void omittedDesignRequirementsDoNotTriggerIncompleteGap() {
        Component mass = mass();
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(mass))
                .planMeta(Map.of(
                        "allow_compiler_satellites", false,
                        "design_requirements", Map.of(
                                "facade", "OMITTED",
                                "entrance", "OMITTED",
                                "roof", "OMITTED",
                                "crown", "OMITTED")))
                .build();

        assertTrue(CompilerSatellitePolicy.missingSatellitesGap(
                plan, mass, false, false, false, false, false, false).isEmpty());
    }

    @Test
    void unspecifiedStillRequiresSatellitesWhenInferDisabled() {
        Component mass = mass();
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(mass))
                .planMeta(Map.of("allow_compiler_satellites", false))
                .build();

        assertTrue(CompilerSatellitePolicy.missingSatellitesGap(
                plan, mass, false, false, false, false, false, false).isPresent());
    }

    @Test
    void requiredCrownWithExplicitCrownPasses() {
        Component mass = mass();
        LlmPlan plan = planWithCrownRequired();

        assertTrue(CompilerSatellitePolicy.missingSatellitesGap(
                plan, mass, true, true, true, false, true, false).isEmpty());
    }

    @Test
    void requiredCrownWithoutCrownReportsMissing() {
        Component mass = mass();
        LlmPlan plan = planWithCrownRequired();

        var gap = CompilerSatellitePolicy.missingSatellitesGap(
                plan, mass, true, true, true, false, false, false);
        assertTrue(gap.isPresent());
        assertTrue(gap.get().message().contains("CROWN"));
        assertFalse(gap.get().message().contains("FACADE_WINDOWS"));
    }

    private static Component mass() {
        return new Component(
                "MASS_MAIN",
                "body",
                new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 4),
                List.of(),
                Map.of());
    }

    private static LlmPlan planWithCrownRequired() {
        return LlmPlanTestFixtures.builder()
                .components(List.of(mass()))
                .planMeta(Map.of(
                        "allow_compiler_satellites", false,
                        "design_requirements", Map.of(
                                "facade", "OMITTED",
                                "entrance", "OMITTED",
                                "roof", "OMITTED",
                                "crown", "REQUIRED")))
                .build();
    }
}
