package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.ResolvedFootprint;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FootprintShapeRemapRepairTest {

    @Test
    void normalizesHexagonWithoutOctagonSubstitution() {
        Component pavilion = new Component(
                "MASS_MAIN", "pavilion", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 6), List.of("六角亭"),
                Map.of("component_id", "east_pavilion", "shape", "hexagon",
                        "anchor_mode", "min_corner"));
        LlmPlan repaired = FootprintShapeRemapRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(pavilion))
                .build());
        Component out = repaired.components().getFirst();
        assertEquals("hexagon", String.valueOf(out.params().get("shape")));
        assertFalse("octagon".equalsIgnoreCase(String.valueOf(out.params().get("shape"))));
        assertFalse("octagon".equalsIgnoreCase(String.valueOf(out.params().getOrDefault("plan_type", ""))));
    }

    @Test
    void planTypeHexagonBecomesNativeHexShape() {
        Component pavilion = new Component(
                "MASS_MAIN", "pavilion", new Vec3i(0, 0, 0),
                new Dimensions(9, 9, 6), List.of(),
                Map.of("component_id", "hex_pavilion", "plan_type", "hexagon"));
        LlmPlan repaired = FootprintShapeRemapRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(pavilion))
                .build());
        Component out = repaired.components().getFirst();
        assertEquals("hexagon", String.valueOf(out.params().get("shape")));
        assertEquals("none", String.valueOf(out.params().get("plan_type")));
    }

    @Test
    void hexagonFootprintHasSixMajorFacadeRuns() {
        ResolvedFootprint fp = ResolvedFootprint.resolve(
                new Component("MASS_MAIN", "p", new Vec3i(0, 0, 0),
                        new Dimensions(11, 11, 6), List.of(),
                        Map.of("shape", "hexagon")),
                11, 11);
        assertEquals(ResolvedFootprint.FootprintShape.HEXAGON, fp.shape());
        // Corners of the AABB must be outside a flat-top hexagon.
        assertFalse(fp.contains(0, 0));
        assertFalse(fp.contains(10, 0));
        assertFalse(fp.contains(0, 10));
        assertFalse(fp.contains(10, 10));
        // Center and mid-edge cells stay inside.
        assertTrue(fp.contains(5, 5));
        assertTrue(fp.contains(5, 0));
        assertTrue(fp.contains(0, 5));
        assertTrue(fp.contains(10, 5));
        assertTrue(fp.contains(5, 10));
    }

    @Test
    void remapsProcessionalStepsToRectangle() {
        Component stair = new Component(
                "MASS_SECONDARY", "slot_tholos", new Vec3i(0, 0, -6),
                new Dimensions(7, 5, 2), List.of("frontal processional stair"),
                Map.of("component_id", "processional_stair", "shape", "steps",
                        "host_id", "tholos_cella"));
        LlmPlan repaired = FootprintShapeRemapRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(stair))
                .build());
        assertEquals("rectangle", String.valueOf(repaired.components().getFirst().params().get("shape")));
        ResolvedFootprint fp = ResolvedFootprint.resolve(repaired.components().getFirst(), 7, 5);
        assertEquals(ResolvedFootprint.FootprintShape.RECTANGLE, fp.shape());
    }

    @Test
    void remapsHemisphereRoofShapeToCircle() {
        Component roof = new Component(
                "ROOF", "slot_tholos", new Vec3i(-2, 8, -2),
                new Dimensions(13, 13, 3), List.of("hemispherical dome"),
                Map.of("component_id", "dome", "shape", "hemisphere", "roof_type", "dome"));
        LlmPlan repaired = FootprintShapeRemapRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(roof))
                .build());
        assertEquals("circle", String.valueOf(repaired.components().getFirst().params().get("shape")));
    }

    @Test
    void leavesSupportedShapesAlone() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0),
                new Dimensions(16, 12, 8), List.of(),
                Map.of("component_id", "hall", "shape", "rectangle", "plan_type", "none"));
        LlmPlan plan = LlmPlanTestFixtures.builder().components(List.of(hall)).build();
        LlmPlan repaired = FootprintShapeRemapRepair.apply(plan);
        assertTrue(repaired == plan || "rectangle".equals(String.valueOf(
                repaired.components().getFirst().params().get("shape"))));
    }
}
