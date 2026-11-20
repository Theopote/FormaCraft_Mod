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

    @Test void mixedCompoundKeepsIndependentMansionBesideStadium() {
        var stadium = new Component("STRUCTURE", "arena", new Vec3i(80, 0, 0),
                new Dimensions(60, 80, 28), List.of("typology:stadium_bowl"),
                Map.of("component_id", "arena", "building_id", "stadium", "typology_id", "stadium_bowl"));
        var mansion = new Component("MASS_MAIN", "mansion", new Vec3i(0, 0, 0),
                new Dimensions(18, 28, 12), List.of("baroque"),
                Map.of("component_id", "mansion", "building_id", "mansion", "style_profile", "Legacy_Classical_Facade"));
        var roof = new Component("ROOF", "mansion", new Vec3i(0, 12, 0),
                new Dimensions(18, 28, 6), List.of(),
                Map.of("component_id", "mansion_roof", "host_id", "mansion", "building_id", "mansion",
                        "roof_type", "mansard"));
        var facade = new Component("FACADE_WINDOWS", "mansion", new Vec3i(0, 0, 0),
                new Dimensions(18, 28, 12), List.of(),
                Map.of("component_id", "mansion_facade", "host_id", "mansion", "building_id", "mansion"));
        var entrance = new Component("ENTRANCE", "mansion", new Vec3i(0, 0, -1),
                new Dimensions(5, 2, 4), List.of(),
                Map.of("component_id", "mansion_entrance", "host_id", "mansion", "building_id", "mansion"));
        var path = new Component("PATH", "site", new Vec3i(0, 0, -10),
                new Dimensions(4, 20, 1), List.of(), Map.of());
        var plan = List.of(stadium, mansion, roof, facade, entrance, path);

        var filtered = ComponentSatelliteInference.applyTypologyExclusiveFilter(plan);
        assertEquals(6, filtered.size(), "independent mansion tree must survive stadium typology exclusive");
        assertTrue(filtered.stream().anyMatch(c -> "mansion".equals(String.valueOf(c.params().get("component_id")))));
        assertTrue(filtered.stream().anyMatch(c -> "mansion_roof".equals(String.valueOf(c.params().get("component_id")))));
        assertTrue(filtered.stream().anyMatch(c -> "mansion_facade".equals(String.valueOf(c.params().get("component_id")))));
        assertTrue(filtered.stream().anyMatch(c -> "mansion_entrance".equals(String.valueOf(c.params().get("component_id")))));
        assertTrue(filtered.stream().anyMatch(c -> "arena".equals(String.valueOf(c.params().get("component_id")))));
        assertTrue(filtered.stream().anyMatch(c -> "PATH".equals(c.componentType())));
    }

    @Test void sameBuildingRedundantMassStillStrippedBesidePagoda() {
        var pagoda = component("STRUCTURE", Map.of(
                "component_id", "pagoda", "building_id", "pagoda", "typology_id", "dense_eaves_pagoda"));
        var shell = component("MASS_MAIN", Map.of(
                "component_id", "pagoda_shell", "building_id", "pagoda", "host_id", "pagoda"));
        var roof = component("ROOF", Map.of(
                "component_id", "pagoda_roof", "building_id", "pagoda", "host_id", "pagoda"));
        var filtered = ComponentSatelliteInference.applyTypologyExclusiveFilter(List.of(pagoda, shell, roof));
        assertEquals(1, filtered.size());
        assertEquals("STRUCTURE", filtered.get(0).componentType());
    }

    @Test void ambiguousPagodaShellWithoutBuildingIdIsNotTreatedAsSecondBuilding() {
        // Different component_id alone must not enable multi-building retention of a co-located shell.
        var pagoda = new Component("STRUCTURE", "pagoda", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 40), List.of("typology:dense_eaves_pagoda"),
                Map.of("component_id", "pagoda", "typology_id", "dense_eaves_pagoda"));
        var shell = new Component("MASS_MAIN", "pagoda", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 40), List.of(),
                Map.of("component_id", "pagoda_shell"));
        var filtered = ComponentSatelliteInference.applyTypologyExclusiveFilter(List.of(pagoda, shell));
        assertEquals(1, filtered.size());
        assertEquals("STRUCTURE", filtered.get(0).componentType());
        assertEquals("pagoda", filtered.get(0).params().get("component_id"));
    }

    @Test void spatiallySeparatedMansionWithoutBuildingIdSurvivesStadiumExclusive() {
        var stadium = new Component("STRUCTURE", "arena", new Vec3i(80, 0, 0),
                new Dimensions(60, 80, 28), List.of("typology:stadium_bowl"),
                Map.of("component_id", "arena", "typology_id", "stadium_bowl"));
        var mansion = new Component("MASS_MAIN", "mansion", new Vec3i(0, 0, 0),
                new Dimensions(18, 28, 12), List.of("baroque"),
                Map.of("component_id", "mansion"));
        var roof = new Component("ROOF", "mansion", new Vec3i(0, 12, 0),
                new Dimensions(18, 28, 6), List.of(),
                Map.of("component_id", "mansion_roof", "host_id", "mansion", "roof_type", "mansard"));
        var filtered = ComponentSatelliteInference.applyTypologyExclusiveFilter(
                List.of(stadium, mansion, roof));
        assertEquals(3, filtered.size());
        assertTrue(filtered.stream().anyMatch(c -> "mansion".equals(String.valueOf(c.params().get("component_id")))));
    }

    @Test void inferredEntranceInheritsHostLocalStyle() {
        var mass = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(16, 20, 10), List.of(),
                Map.of("component_id", "hui_house", "style_profile", "Chinese_Vernacular_Huizhou",
                        "door_width", 3, "door_height", 3));
        var plan = com.formacraft.common.llm.dto.LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .styleProfile("Modern_International")
                .components(List.of(mass))
                .build();
        var entrance = ComponentSatelliteInference.makeEntranceComponent(
                plan, mass, "main", com.formacraft.common.llm.dto.GlobalConstraints.Facing.SOUTH);
        assertNotNull(entrance);
        assertEquals("Chinese_Vernacular_Huizhou", entrance.params().get("style_profile"));
        assertEquals("Chinese_Vernacular_Huizhou",
                com.formacraft.common.style.EffectiveBuildingStyleResolver.resolve(plan, entrance));
    }
}
