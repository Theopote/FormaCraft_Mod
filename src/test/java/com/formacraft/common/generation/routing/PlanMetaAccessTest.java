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
        // Explicit empty meta (no key) → false. Fixture builder().build() still injects true
        // for legacy test convenience — that is not the production absent-key contract.
        assertFalse(PlanMetaAccess.allowCompilerSatellites(
                LlmPlanTestFixtures.builder().planMeta(Map.of()).build()));
        assertFalse(PlanMetaAccess.allowCompilerSatellites(
                LlmPlanTestFixtures.builder()
                        .planMeta(Map.of("allow_compiler_satellites", false))
                        .build()));
        assertTrue(PlanMetaAccess.allowCompilerSatellites(
                LlmPlanTestFixtures.builder()
                        .planMeta(Map.of("allow_compiler_satellites", true))
                        .build()));
    }
}
