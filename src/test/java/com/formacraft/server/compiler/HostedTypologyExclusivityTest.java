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

    @Test void minCornerAdjacentBuildingsWithoutBuildingIdAreNotFalselyOverlapped() {
        // Pagoda [0,12) and hall [13,93) with min_corner — one-block gap; center-anchor math would overlap.
        var pagoda = new Component("STRUCTURE", "pagoda", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 40), List.of("typology:dense_eaves_pagoda"),
                Map.of("component_id", "pagoda", "typology_id", "dense_eaves_pagoda",
                        "anchor_mode", "min_corner"));
        var hall = new Component("MASS_MAIN", "hall", new Vec3i(13, 0, 0),
                new Dimensions(80, 40, 18), List.of(),
                Map.of("component_id", "main_hall", "anchor_mode", "min_corner"));
        var filtered = ComponentSatelliteInference.applyTypologyExclusiveFilter(List.of(pagoda, hall));
        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().anyMatch(c -> "main_hall".equals(String.valueOf(c.params().get("component_id")))));
    }

    @Test void distinctSlotAnchorsSeparateBuildingsWithIdenticalLocalOrigins() {
        var pagoda = new Component("STRUCTURE", "pagoda_slot", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 40), List.of("typology:dense_eaves_pagoda"),
                Map.of("component_id", "pagoda", "typology_id", "dense_eaves_pagoda"));
        var hall = new Component("MASS_MAIN", "hall_slot", new Vec3i(0, 0, 0),
                new Dimensions(40, 30, 16), List.of(),
                Map.of("component_id", "main_hall"));
        Map<String, Slot> slots = Map.of(
                "pagoda_slot", new Slot("pagoda_slot", new Vec3i(0, 0, 0), null, null, null, null),
                "hall_slot", new Slot("hall_slot", new Vec3i(100, 0, 0), null, null, null, null));
        var filtered = ComponentSatelliteInference.applyTypologyExclusiveFilter(
                List.of(pagoda, hall), slots);
        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().anyMatch(c -> "main_hall".equals(String.valueOf(c.params().get("component_id")))));
        assertTrue(com.formacraft.server.generation.GenerationCompileContext
                .hasIndependentCompositionalBuilding(List.of(pagoda, hall), slots));
    }

    @Test void missingGeometryDoesNotInventOneByOneFootprint() {
        com.formacraft.server.assembly.AssemblyCompileDiagnostics.clear();
        var pagoda = new Component("STRUCTURE", "pagoda", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 40), List.of("typology:dense_eaves_pagoda"),
                Map.of("component_id", "pagoda", "typology_id", "dense_eaves_pagoda"));
        // No dimensions → AMBIGUOUS keep + gap (never invent 1×1 or silent delete).
        var shell = new Component("MASS_MAIN", "pagoda", null, null, List.of(),
                Map.of("component_id", "pagoda_shell"));
        var filtered = ComponentSatelliteInference.applyTypologyExclusiveFilter(List.of(pagoda, shell));
        assertEquals(2, filtered.size());
        assertEquals("E_COMPONENT_OWNERSHIP_AMBIGUOUS",
                com.formacraft.server.assembly.AssemblyCompileDiagnostics.get().code());
        com.formacraft.server.assembly.AssemblyCompileDiagnostics.clear();
    }

    @Test void soleIncompleteAnnexIsKeptWithOwnershipGap() {
        com.formacraft.server.assembly.AssemblyCompileDiagnostics.clear();
        var pagoda = new Component("STRUCTURE", "pagoda", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 40), List.of("typology:dense_eaves_pagoda"),
                Map.of("component_id", "pagoda", "typology_id", "dense_eaves_pagoda"));
        var annex = new Component("MASS_MAIN", "annex", null, null, List.of(),
                Map.of("component_id", "incomplete_annex"));
        var filtered = ComponentSatelliteInference.applyTypologyExclusiveFilter(List.of(pagoda, annex));
        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().anyMatch(c -> "incomplete_annex".equals(String.valueOf(c.params().get("component_id")))));
        assertEquals("E_COMPONENT_OWNERSHIP_AMBIGUOUS",
                com.formacraft.server.assembly.AssemblyCompileDiagnostics.get().code());
        com.formacraft.server.assembly.AssemblyCompileDiagnostics.clear();
    }

    @Test void unresolvableHostIdDoesNotBypassOverlapExclusiveStrip() {
        com.formacraft.server.assembly.AssemblyCompileDiagnostics.clear();
        var pagoda = new Component("STRUCTURE", "pagoda", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 40), List.of("typology:dense_eaves_pagoda"),
                Map.of("component_id", "pagoda", "typology_id", "dense_eaves_pagoda"));
        var shell = new Component("MASS_MAIN", "pagoda", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 40), List.of(),
                Map.of("component_id", "pagoda_shell", "host_id", "missing_host"));
        var hall = new Component("MASS_MAIN", "hall", new Vec3i(100, 0, 0),
                new Dimensions(40, 30, 16), List.of(),
                Map.of("component_id", "main_hall"));
        var filtered = ComponentSatelliteInference.applyTypologyExclusiveFilter(
                List.of(pagoda, shell, hall));
        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().noneMatch(c -> "pagoda_shell".equals(String.valueOf(c.params().get("component_id")))));
        com.formacraft.server.assembly.AssemblyCompileDiagnostics.clear();
    }

    @Test void mixedCompoundStripsOverlappingShellButKeepsDistantHall() {
        var pagoda = new Component("STRUCTURE", "pagoda", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 40), List.of("typology:dense_eaves_pagoda"),
                Map.of("component_id", "pagoda", "typology_id", "dense_eaves_pagoda"));
        var shell = new Component("MASS_MAIN", "pagoda", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 40), List.of(),
                Map.of("component_id", "pagoda_shell"));
        var hall = new Component("MASS_MAIN", "hall", new Vec3i(100, 0, 0),
                new Dimensions(40, 30, 16), List.of(),
                Map.of("component_id", "main_hall"));
        var filtered = ComponentSatelliteInference.applyTypologyExclusiveFilter(
                List.of(pagoda, shell, hall));
        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().anyMatch(c -> "pagoda".equals(String.valueOf(c.params().get("component_id")))));
        assertTrue(filtered.stream().anyMatch(c -> "main_hall".equals(String.valueOf(c.params().get("component_id")))));
        assertTrue(filtered.stream().noneMatch(c -> "pagoda_shell".equals(String.valueOf(c.params().get("component_id")))));
    }

    @Test void distinctSlotsWithMissingGeometryAreAmbiguousNotIndependent() {
        com.formacraft.server.assembly.AssemblyCompileDiagnostics.clear();
        var pagoda = new Component("STRUCTURE", "tower_main", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 40), List.of("typology:dense_eaves_pagoda"),
                Map.of("component_id", "pagoda", "typology_id", "dense_eaves_pagoda"));
        var shell = new Component("MASS_MAIN", "tower_shell", null, null, List.of(),
                Map.of("component_id", "pagoda_shell"));
        Map<String, Slot> slots = Map.of(
                "tower_main", new Slot("tower_main", new Vec3i(0, 0, 0), null, null, null, null),
                "tower_shell", new Slot("tower_shell", new Vec3i(0, 0, 0), null, null, null, null));
        var filtered = ComponentSatelliteInference.applyTypologyExclusiveFilter(
                List.of(pagoda, shell), slots);
        assertEquals(2, filtered.size());
        assertFalse(com.formacraft.server.generation.GenerationCompileContext
                .hasIndependentCompositionalBuilding(List.of(pagoda, shell), slots));
        assertEquals("E_COMPONENT_OWNERSHIP_AMBIGUOUS",
                com.formacraft.server.assembly.AssemblyCompileDiagnostics.get().code());
        com.formacraft.server.assembly.AssemblyCompileDiagnostics.clear();
    }

    @Test void paramsSlotIdResolvesPlanSpaceFootprint() {
        var pagoda = new Component("STRUCTURE", null, new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 40), List.of("typology:dense_eaves_pagoda"),
                Map.of("component_id", "pagoda", "typology_id", "dense_eaves_pagoda", "slot_id", "pagoda_slot"));
        var hall = new Component("MASS_MAIN", null, new Vec3i(0, 0, 0),
                new Dimensions(40, 30, 16), List.of(),
                Map.of("component_id", "east_hall", "slot_id", "east_slot"));
        Map<String, Slot> slots = Map.of(
                "pagoda_slot", new Slot("pagoda_slot", new Vec3i(0, 0, 0), null, null, null, null),
                "east_slot", new Slot("east_slot", new Vec3i(100, 0, 0), null, null, null, null));
        var filtered = ComponentSatelliteInference.applyTypologyExclusiveFilter(
                List.of(pagoda, hall), slots);
        assertEquals(2, filtered.size());
    }

    @Test void distinctBuildingIdSurvivesXzOverlapWithExclusiveHost() {
        com.formacraft.server.assembly.AssemblyCompileDiagnostics.clear();
        var stadium = new Component("STRUCTURE", "arena", new Vec3i(0, 0, 0),
                new Dimensions(60, 80, 20), List.of("typology:stadium_bowl"),
                Map.of("component_id", "arena", "building_id", "stadium_A", "typology_id", "stadium_bowl"));
        // View tower cantilever overlaps stadium XZ; distinct building_id + higher Y.
        var tower = new Component("MASS_MAIN", "view", new Vec3i(20, 12, 0),
                new Dimensions(16, 16, 24), List.of(),
                Map.of("component_id", "view_tower", "building_id", "view_tower_B"));
        var filtered = ComponentSatelliteInference.applyTypologyExclusiveFilter(List.of(stadium, tower));
        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().anyMatch(c -> "view_tower".equals(String.valueOf(c.params().get("component_id")))));
        assertFalse(com.formacraft.server.assembly.AssemblyCompileDiagnostics.hasGap());
        com.formacraft.server.assembly.AssemblyCompileDiagnostics.clear();
    }

    @Test void ambiguousAnnexInMixedCompoundIsKeptWithOwnershipGap() {
        com.formacraft.server.assembly.AssemblyCompileDiagnostics.clear();
        var pagoda = new Component("STRUCTURE", "pagoda", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 40), List.of("typology:dense_eaves_pagoda"),
                Map.of("component_id", "pagoda", "typology_id", "dense_eaves_pagoda"));
        var hall = new Component("MASS_MAIN", "hall", new Vec3i(100, 0, 0),
                new Dimensions(40, 30, 16), List.of(),
                Map.of("component_id", "main_hall"));
        var annex = new Component("MASS_MAIN", "annex", null, null, List.of(),
                Map.of("component_id", "incomplete_annex"));
        var filtered = ComponentSatelliteInference.applyTypologyExclusiveFilter(
                List.of(pagoda, hall, annex));
        assertEquals(3, filtered.size());
        assertTrue(filtered.stream().anyMatch(c -> "incomplete_annex".equals(String.valueOf(c.params().get("component_id")))));
        assertTrue(com.formacraft.server.assembly.AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_COMPONENT_OWNERSHIP_AMBIGUOUS",
                com.formacraft.server.assembly.AssemblyCompileDiagnostics.get().code());
        com.formacraft.server.assembly.AssemblyCompileDiagnostics.clear();
    }

    @Test void sameBuildingIdExclusiveRootsAreNotMultiBuildingByCount() {
        var a = new Component("STRUCTURE", "a", new Vec3i(0, 0, 0),
                new Dimensions(20, 20, 10), List.of("typology:stadium_bowl"),
                Map.of("component_id", "bowl_a", "building_id", "stadium", "typology_id", "stadium_bowl"));
        var b = new Component("STRUCTURE", "b", new Vec3i(30, 0, 0),
                new Dimensions(20, 20, 10), List.of("typology:stadium_bowl"),
                Map.of("component_id", "bowl_b", "building_id", "stadium", "typology_id", "stadium_bowl"));
        var shell = new Component("MASS_MAIN", "a", new Vec3i(0, 0, 0),
                new Dimensions(20, 20, 10), List.of(),
                Map.of("component_id", "redundant_shell", "building_id", "stadium"));
        assertFalse(com.formacraft.server.generation.GenerationCompileContext
                .exclusiveRootsSpanDistinctBuildings(List.of(a, b)));
        var filtered = ComponentSatelliteInference.applyTypologyExclusiveFilter(List.of(a, b, shell));
        // Sole building identity → strip compositional shell; keep both exclusive STRUCTURE.
        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(c -> "STRUCTURE".equals(c.componentType())));
    }

    @Test void authoredWidthOneDoesNotInventOverlapViaMassNormalize() {
        var stadium = new Component("STRUCTURE", "arena", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 10), List.of("typology:stadium_bowl"),
                Map.of("component_id", "arena", "typology_id", "stadium_bowl"));
        // Center at x=6, width=1 → [6,7); stadium center 0 w=12 → [-6,6). Touching, not overlapping half-open.
        var slim = new Component("MASS_MAIN", "slim", new Vec3i(6, 0, 0),
                new Dimensions(1, 1, 4), List.of(),
                Map.of("component_id", "slim_tower"));
        var filtered = ComponentSatelliteInference.applyTypologyExclusiveFilter(List.of(stadium, slim));
        assertEquals(2, filtered.size());
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
