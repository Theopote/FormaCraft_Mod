package com.formacraft.common.generation.design;

import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Local USER_EXPLICIT on one mass must not plan-lock sibling masses. */
class LocalDesignProvenanceScopeTest {
    @Test
    void componentLevelRoofLockDoesNotBlockSiblingMassWithoutPlanLock() {
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        hallParams.put("roof_type", "xieshan");
        hallParams.put("design_sources", Map.of("roof_type", "USER_EXPLICIT"));

        // Sibling has no component-level lock — must stay mutable when plan-level is absent.
        Map<String, Object> wingParams = new HashMap<>();
        wingParams.put("component_id", "side_wing");
        wingParams.put("roof_type", "flat");

        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("Chinese_Classical")
                .anchor(new Vec3i(0, 64, 0))
                .components(List.of(
                        new Component("MASS_MAIN", "hall", new Vec3i(0, 0, 0),
                                new Dimensions(20, 14, 9), List.of(), hallParams),
                        new Component("MASS_MAIN", "wing", new Vec3i(24, 0, 0),
                                new Dimensions(10, 8, 5), List.of(), wingParams)
                ))
                // No plan-level design_sources roof_type lock.
                .build();

        assertFalse(DesignProvenance.canMutateParam(plan, hallParams, "roof_type"),
                "hall USER_EXPLICIT must lock its own roof");
        assertTrue(DesignProvenance.canMutateParam(plan, wingParams, "roof_type"),
                "wing remains mutable when plan-level roof_type is unlocked");
    }

    @Test
    void planLevelUserExplicitLocksEveryMass() {
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        Map<String, Object> wingParams = new HashMap<>();
        wingParams.put("component_id", "side_wing");

        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("Chinese_Classical")
                .anchor(new Vec3i(0, 64, 0))
                .designSources(Map.of("roof_type", "USER_EXPLICIT"))
                .components(List.of(
                        new Component("MASS_MAIN", "hall", new Vec3i(0, 0, 0),
                                new Dimensions(20, 14, 9), List.of(), hallParams),
                        new Component("MASS_MAIN", "wing", new Vec3i(24, 0, 0),
                                new Dimensions(10, 8, 5), List.of(), wingParams)
                ))
                .build();

        assertFalse(DesignProvenance.canMutateParam(plan, hallParams, "roof_type"));
        assertFalse(DesignProvenance.canMutateParam(plan, wingParams, "roof_type"));
    }
}
