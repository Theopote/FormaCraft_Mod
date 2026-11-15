package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostedTypologyExclusivityTest {
    @Test void realGameMinimalTowerRetainsItsEnvelope() throws Exception {
        try (var input = getClass().getResourceAsStream("/style_quality_20261115/minimal_tower.json")) {
            assertNotNull(input);
            var plan = com.formacraft.common.llm.parser.LlmPlanParser.parse(
                    new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            var filtered = ComponentSatelliteInference.applyTypologyExclusiveFilter(plan.components());
            assertEquals(plan.components(), filtered);
            assertTrue(filtered.stream().anyMatch(c -> "MASS_MAIN".equals(c.componentType())
                    && c.dimensions().width() == 12 && c.dimensions().height() == 30));
        }
    }
    private Component component(String type, Map<String, Object> params) {
        return new Component(type, "tower_slot", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 30), List.of(), params);
    }

    @Test void interiorStairTypologyDoesNotRemoveTowerEnvelope() {
        var mass = component("MASS_MAIN", Map.of("component_id", "tower_main"));
        var stair = component("STRUCTURE", Map.of("typology_id", "spiral_stair_tower", "host_id", "tower_main"));
        var roof = component("ROOF", Map.of("host_id", "tower_main"));
        var facade = component("FACADE_WINDOWS", Map.of("host_id", "tower_main"));
        var plan = List.of(mass, stair, roof, facade);
        assertFalse(ComponentSatelliteInference.isWholeBuildingTypology(stair));
        assertEquals(plan, ComponentSatelliteInference.applyTypologyExclusiveFilter(plan));
    }

    @Test void standaloneTypologyStillOwnsItsBuilding() {
        var whole = component("STRUCTURE", Map.of("typology_id", "dense_eaves_pagoda"));
        var mass = component("MASS_MAIN", Map.of());
        assertTrue(ComponentSatelliteInference.isWholeBuildingTypology(whole));
        assertEquals(List.of(whole), ComponentSatelliteInference.applyTypologyExclusiveFilter(List.of(whole, mass)));
    }
}
