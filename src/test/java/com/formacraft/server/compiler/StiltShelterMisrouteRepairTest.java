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

import static org.junit.jupiter.api.Assertions.*;

class StiltShelterMisrouteRepairTest {

    @Test
    void suspensionBridgeStructureUnderStiltIntentEmitsGapAndKeepsComponents() {
        Component bridge = new Component(
                "STRUCTURE", "main", new Vec3i(0, 0, 0),
                new Dimensions(40, 8, 18), List.of("suspension_bridge"),
                Map.of("component_id", "span", "typology_id", "suspension_bridge"));
        Map<String, Object> meta = new HashMap<>();
        meta.put("request_intent_text", "山坡上建一座吊脚楼避难所，带吊桥");
        meta.put("request_intent", Map.of(
                "original_text", "山坡上建一座吊脚楼避难所，带吊桥"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(bridge))
                .planMeta(meta)
                .build();

        LlmPlan out = StiltShelterMisrouteRepair.apply(plan);
        assertTrue(out.hasCapabilityGap());
        assertEquals("E_STILT_SHELTER_ROUTE_UNSUPPORTED", out.capabilityGap().code());
        assertEquals(1, out.components().size());
        assertEquals("STRUCTURE", out.components().getFirst().componentType());
        assertEquals(40, out.components().getFirst().dimensions().width());
    }

    @Test
    void featureTagAloneDoesNotTriggerStiltGap() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of("suspension_bridge_look"),
                Map.of("component_id", "hall"));
        Map<String, Object> meta = new HashMap<>();
        meta.put("request_intent_text", "吊脚楼避难所");
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(mass))
                .planMeta(meta)
                .build();

        LlmPlan out = StiltShelterMisrouteRepair.apply(plan);
        assertFalse(out.hasCapabilityGap());
        assertEquals("MASS_MAIN", out.components().getFirst().componentType());
    }

    @Test
    void existingStiltCompositionIsLeftAlone() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 4, 0),
                new Dimensions(10, 9, 6), List.of("stilt_shelter"),
                Map.of("component_id", "stilt_main", "foundation_type", "stilts"));
        Component posts = new Component(
                "FOUNDATION", "main", new Vec3i(0, 0, 0),
                new Dimensions(10, 9, 4), List.of(),
                Map.of("component_id", "posts", "foundation_type", "stilts"));
        Component bridge = new Component(
                "STRUCTURE", "main", new Vec3i(0, 0, 20),
                new Dimensions(20, 4, 10), List.of(),
                Map.of("component_id", "wrong", "typology_id", "suspension_bridge"));
        Map<String, Object> meta = new HashMap<>();
        meta.put("request_intent_text", "吊脚楼");
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(mass, posts, bridge))
                .planMeta(meta)
                .build();

        LlmPlan out = StiltShelterMisrouteRepair.apply(plan);
        assertFalse(out.hasCapabilityGap());
        assertEquals(3, out.components().size());
    }
}
