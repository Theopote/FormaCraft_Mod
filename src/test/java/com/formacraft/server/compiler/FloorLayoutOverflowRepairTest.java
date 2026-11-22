package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.typology.UserConstraintContract;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FloorLayoutOverflowRepairTest {

    @BeforeEach
    void clearDiagnostics() {
        AssemblyCompileDiagnostics.clear();
    }

    @AfterEach
    void clearAfter() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void sealedHeightWithFiveFloorsEmitsGapWithoutReducingCount() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "tower");
        params.put("floor_count", 5);
        params.put("floor_height", 4);
        params.put("height", 10);
        UserConstraintContract.stampVerified(params, "height");
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 10), List.of(), params);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(List.of(mass))
                .build();

        LlmPlan out = FloorLayoutOverflowRepair.apply(plan);
        assertTrue(out.hasCapabilityGap(), "must not silently drop floor_count");
        assertEquals("E_FLOOR_LAYOUT_CONFLICT", out.capabilityGap().code());
        assertEquals(5, ((Number) out.components().getFirst().params().get("floor_count")).intValue());
        assertEquals(10, out.components().getFirst().dimensions().height());
    }

    @Test
    void unsealedHeightGrowsEnvelopeForFiveFloors() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "tower");
        params.put("floor_count", 5);
        params.put("floor_height", 4);
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 10), List.of(), params);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(List.of(mass))
                .build();

        LlmPlan out = FloorLayoutOverflowRepair.apply(plan);
        assertFalse(out.hasCapabilityGap());
        assertEquals(20, out.components().getFirst().dimensions().height());
        assertEquals(5, ((Number) out.components().getFirst().params().get("floor_count")).intValue());
    }

    @Test
    void softFloorCountStillNeverSilentlyReduced() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "tower");
        params.put("floor_count", 5);
        params.put("height", 10);
        UserConstraintContract.stampVerified(params, "height");
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 10), List.of(), params);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(List.of(mass))
                .build();

        LlmPlan out = FloorLayoutOverflowRepair.apply(plan);
        assertTrue(out.hasCapabilityGap());
        assertEquals(5, ((Number) out.components().getFirst().params().get("floor_count")).intValue());
    }

    @Test
    void modelPlannedHeightAndStoreyBandsArePreservedWhenTheyFit() {
        assertPlannedLayoutPreserved(14, false);
    }

    @Test
    void conflictingModelPlannedHeightAndStoreyBandsReportGapWithoutRewriting() {
        assertPlannedLayoutPreserved(10, true);
    }

    private static void assertPlannedLayoutPreserved(int height, boolean conflict) {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0), new Dimensions(12, 12, height), List.of(),
                Map.of("component_id", "atrium", "floor_count", 2, "floorHeight", 6,
                        "design_sources", Map.of("dimensions.height", "AI_PLANNED",
                                "floor_height", "AI_PLANNED")));
        LlmPlan plan = LlmPlanTestFixtures.builder().components(List.of(mass)).build();
        LlmPlan out = FloorLayoutOverflowRepair.apply(plan);

        assertEquals(List.of(mass), out.components());
        assertEquals(conflict, out.hasCapabilityGap());
        if (conflict) {
            assertEquals("E_FLOOR_LAYOUT_CONFLICT", out.capabilityGap().code());
            assertEquals("components[0]", out.capabilityGap().path());
        }
    }

    @Test
    void softFloorHeightMayClampIntoSealedEnvelope() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "tower");
        params.put("floor_count", 2);
        params.put("floor_height", 6);
        params.put("height", 10);
        UserConstraintContract.stampVerified(params, "height");
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 10), List.of(), params);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(List.of(mass))
                .build();

        LlmPlan out = FloorLayoutOverflowRepair.apply(plan);
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        assertEquals(2, ((Number) out.components().getFirst().params().get("floor_count")).intValue());
        assertEquals(5, ((Number) out.components().getFirst().params().get("floor_height")).intValue());
        assertEquals(10, out.components().getFirst().dimensions().height());
    }
}
