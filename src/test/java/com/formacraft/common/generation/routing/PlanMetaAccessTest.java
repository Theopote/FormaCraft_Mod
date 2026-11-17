package com.formacraft.common.generation.routing;

import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanMetaAccessTest {

    @Test
    void allowCompilerSatellitesIsOptInOnly() {
        assertFalse(PlanMetaAccess.allowCompilerSatellites(null));
        // Fixture no longer auto-injects: bare builder().build() matches production absent→false.
        assertFalse(PlanMetaAccess.allowCompilerSatellites(LlmPlanTestFixtures.builder().build()));
        assertFalse(PlanMetaAccess.allowCompilerSatellites(
                LlmPlanTestFixtures.builder().planMeta(Map.of()).build()));
        assertFalse(PlanMetaAccess.allowCompilerSatellites(
                LlmPlanTestFixtures.builder()
                        .planMeta(Map.of("allow_compiler_satellites", false))
                        .build()));
        assertTrue(PlanMetaAccess.allowCompilerSatellites(
                LlmPlanTestFixtures.builder()
                        .allowCompilerSatellites(true)
                        .build()));
        assertTrue(PlanMetaAccess.allowCompilerSatellites(
                LlmPlanTestFixtures.builder()
                        .planMeta(Map.of("allow_compiler_satellites", true))
                        .build()));
    }
}
