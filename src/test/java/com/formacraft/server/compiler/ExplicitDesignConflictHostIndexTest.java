package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplicitDesignConflictHostIndexTest {

    @Test
    void dropsWindowsOnMassSecondaryWhenHostDisablesWindows() {
        Component annex = new Component(
                "MASS_SECONDARY", "annex",
                new Vec3i(12, 0, 0),
                new Dimensions(8, 8, 6),
                List.of("open_pavilion"),
                Map.of("component_id", "side_pavilion", "window_style", "none", "open", true));
        Component windows = new Component(
                "FACADE_WINDOWS", "annex",
                new Vec3i(12, 2, 0),
                new Dimensions(8, 1, 2),
                List.of(),
                Map.of("component_id", "side_win", "host_id", "side_pavilion"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(annex, windows))
                .build();

        LlmPlan out = ExplicitDesignConflictRepair.apply(plan);
        assertEquals(1, out.components().size());
        assertEquals("MASS_SECONDARY", out.components().getFirst().componentType());
    }

    @Test
    void validatorSeesMassSecondaryWindowConflict() {
        Component annex = new Component(
                "MASS_SECONDARY", "annex",
                new Vec3i(12, 0, 0),
                new Dimensions(8, 8, 6),
                List.of(),
                Map.of("component_id", "side_pavilion", "window_style", "none"));
        Component windows = new Component(
                "FACADE_WINDOWS", "annex",
                new Vec3i(12, 2, 0),
                new Dimensions(8, 1, 2),
                List.of(),
                Map.of("component_id", "side_win", "host_id", "side_pavilion"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(annex, windows))
                .build();

        Optional<CapabilityGap> gap = ExplicitDesignConflictValidator.check(plan, plan.components());
        assertTrue(gap.isPresent());
        assertEquals("E_EXPLICIT_DESIGN_CONFLICT", gap.get().code());
    }

    @Test
    void lockedWindowsConflictWithHostOptOutEmitsGap() {
        Component annex = new Component(
                "MASS_SECONDARY", "annex",
                new Vec3i(12, 0, 0),
                new Dimensions(8, 8, 6),
                List.of(),
                Map.of("component_id", "side_pavilion", "window_style", "none"));
        Map<String, Object> winParams = new HashMap<>();
        winParams.put("component_id", "side_win");
        winParams.put("host_id", "side_pavilion");
        winParams.put("window_style", "lattice");
        winParams.put("design_sources", Map.of("window_style", "USER_EXPLICIT"));
        Component windows = new Component(
                "FACADE_WINDOWS", "annex",
                new Vec3i(12, 2, 0),
                new Dimensions(8, 1, 2),
                List.of(),
                winParams);
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(annex, windows))
                .build();

        LlmPlan out = ExplicitDesignConflictRepair.apply(plan);
        assertTrue(out.hasCapabilityGap());
        assertEquals("E_EXPLICIT_DESIGN_CONFLICT", out.capabilityGap().code());
        assertEquals(2, out.components().size());
        assertFalse(out.components().stream().noneMatch(c -> "FACADE_WINDOWS".equals(c.componentType())));
    }
}
