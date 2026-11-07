package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FootprintShapeRemapRepairTest {

    @Test
    void remapsHexagonShapeToOctagonCutCorners() {
        Component pavilion = new Component(
                "MASS_MAIN", "pavilion", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 6), List.of("六角亭"),
                Map.of("component_id", "east_pavilion", "shape", "hexagon",
                        "anchor_mode", "min_corner"));
        LlmPlan repaired = FootprintShapeRemapRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(pavilion))
                .build());
        Component out = repaired.components().getFirst();
        assertEquals("octagon", String.valueOf(out.params().get("shape")));
        assertEquals("octagon", String.valueOf(out.params().get("plan_type")));
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
