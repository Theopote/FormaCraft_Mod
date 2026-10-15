package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilerSatellitePolicyTest {

    @Test
    void omittedDesignRequirementsDoNotTriggerIncompleteGap() {
        Component mass = new Component(
                "MASS_MAIN",
                "body",
                new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 4),
                List.of(),
                Map.of());
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
                plan, mass, false, false, false, false, false).isEmpty());
    }

    @Test
    void unspecifiedStillRequiresSatellitesWhenInferDisabled() {
        Component mass = new Component(
                "MASS_MAIN",
                "body",
                new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 4),
                List.of(),
                Map.of());
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(mass))
                .planMeta(Map.of("allow_compiler_satellites", false))
                .build();

        assertTrue(CompilerSatellitePolicy.missingSatellitesGap(
                plan, mass, false, false, false, false, false).isPresent());
    }
}
