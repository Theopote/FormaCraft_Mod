package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DenseEavesPagodaMisrouteRepairTest {

    @Test
    void stripsDenseEavesFromAsymmetricWastelandPavilion() {
        Component structure = new Component(
                "STRUCTURE", "tower",
                new Vec3i(0, 0, 0),
                new Dimensions(20, 20, 28),
                List.of("typology:dense_eaves_pagoda"),
                Map.of("component_id", "tower", "typology_id", "dense_eaves_pagoda", "footprint", "octagon"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(structure))
                .planMeta(Map.of(
                        "request_intent_text", "生成一座 20x20 格的非对称废土风格中式楼阁。"))
                .build();

        LlmPlan out = DenseEavesPagodaMisrouteRepair.apply(plan);
        Component c = out.components().get(0);
        assertEquals("MASS_MAIN", c.componentType());
        assertNotEquals("dense_eaves_pagoda", String.valueOf(c.params().get("typology_id")));
    }

    @Test
    void forcesSquareFootprintForFourCornerTower() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "tower_body");
        params.put("typology_id", "dense_eaves_pagoda");
        params.put("floor_count", 3);
        Component structure = new Component(
                "STRUCTURE", "tower",
                new Vec3i(0, 0, 0),
                new Dimensions(16, 16, 23),
                List.of("typology:dense_eaves_pagoda", "four_corner"),
                params);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(structure))
                .planMeta(Map.of(
                        "request_intent_text", "生成一座 16x16 格的中式三层四角塔楼。"))
                .build();

        LlmPlan out = DenseEavesPagodaMisrouteRepair.apply(plan);
        Component c = out.components().get(0);
        assertEquals("square", c.params().get("footprint"));
        assertTrue(c.dimensions().height() >= 34, "height=" + c.dimensions().height());
        assertTrue(((Number) c.params().get("floor_height")).intValue() >= 6);
        assertEquals(true, c.params().get("corner_lanterns"));
        assertEquals("dense_eaves_pagoda", c.params().get("typology_id"));
    }

    @Test
    void fourCornerWoodPostsDoNotForcePagodaStoreyHeight() {
        Component pavilion = new Component(
                "MASS_MAIN", "pavilion_1",
                new Vec3i(0, 0, 0),
                new Dimensions(5, 5, 3),
                List.of("column"),
                Map.of("component_id", "pav1_main", "open", true, "wall_type", "none"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(pavilion))
                .planMeta(Map.of(
                        "request_intent_text", "建三座开敞木亭，每座四角木柱支撑双坡屋顶。"))
                .playerFidelityNoticeZh("组合式生成「建三座…四角木柱…」")
                .build();

        LlmPlan out = DenseEavesPagodaMisrouteRepair.apply(plan);
        assertEquals(3, out.components().get(0).dimensions().height());
    }
}
