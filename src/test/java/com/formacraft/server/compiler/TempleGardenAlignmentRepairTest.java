package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live prompt regressions: 道观 foundation/entrance join, deep 山门 morphology,
 * garden hall-vs-pavilion stilts/overlap, stale ThreadLocal gaps.
 */
class TempleGardenAlignmentRepairTest {

    @BeforeEach
    void clearDiagnostics() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void deepCeremonialGateHallPassesSemanticGate() {
        Component podium = new Component(
                "FOUNDATION", "gate", new Vec3i(0, 0, 0),
                new Dimensions(26, 22, 2), List.of("stone_plinth"),
                Map.of("component_id", "podium", "plinth", true, "host_id", "gate_body"));
        Component body = new Component(
                "MASS_MAIN", "gate", new Vec3i(0, 2, 0),
                new Dimensions(22, 27, 13),
                List.of("gate_bays", "bay_division:3"),
                Map.of(
                        "component_id", "gate_body",
                        "bays", 3,
                        "entrance_type", "gate_door",
                        "door_width", 4));
        Component roof = new Component(
                "ROOF", "gate", new Vec3i(-2, 15, -2),
                new Dimensions(26, 31, 3), List.of(),
                Map.of("component_id", "gate_roof", "host_id", "gate_body", "roof_type", "xuanshan"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .distinguishingFeatures(List.of("寺庙山门"))
                .components(List.of(podium, body, roof))
                .planMeta(Map.of(
                        "request_intent", Map.of(
                                "building_class", "gate",
                                "confidence", 0.9,
                                "original_text", "生成一座中式寺庙山门")))
                .build();
        assertTrue(PlanSemanticValidator.check(plan).isEmpty(),
                () -> PlanSemanticValidator.check(plan).map(CapabilityGap::message).orElse(""));
    }

    @Test
    void gardenHallIsNotForcedOpenWhenStiltsHostedOnHall() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0),
                new Dimensions(16, 12, 8), List.of("main_hall"),
                Map.of("component_id", "hall_main", "building_role", "hall_main",
                        "wall_block", "minecraft:stone_bricks"));
        Component pavilion = new Component(
                "MASS_MAIN", "pavilion", new Vec3i(11, 0, 3),
                new Dimensions(6, 6, 5), List.of("pavilion", "亭"),
                Map.of("component_id", "pavilion_water", "building_role", "pavilion",
                        "wall_block", "minecraft:oak_log"));
        Component stiltsOnHall = new Component(
                "FOUNDATION", "hall", new Vec3i(0, -3, 0),
                new Dimensions(16, 12, 3), List.of("stilts"),
                Map.of("component_id", "found_hall", "host_id", "hall_main",
                        "foundation_type", "stilts"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .distinguishingFeatures(List.of("江南园林", "亭"))
                .components(List.of(hall, pavilion, stiltsOnHall))
                .planMeta(Map.of(
                        "request_intent", Map.of(
                                "building_class", "garden",
                                "confidence", 0.9,
                                "original_text", "生成一座江南园林")))
                .build();

        AssemblyCompileDiagnostics.clear();
        var patches = ComponentPlanCompiler.compile(plan, null, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());

        // Hall must keep enclosed walls; pavilion may be open-frame.
        LlmPlan repaired = PavilionOpenFrameRepair.apply(plan);
        Component hallAfter = repaired.components().stream()
                .filter(c -> "hall_main".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertNotEquals(Boolean.TRUE, hallAfter.params().get("open"));
        assertNotEquals("none", String.valueOf(hallAfter.params().getOrDefault("wall_type", "solid")));
    }

    @Test
    void overlappingPavilionIsNudgedOffHallFootprint() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, -8),
                new Dimensions(16, 12, 8), List.of("main_hall"),
                Map.of("component_id", "hall_main", "building_role", "hall_main"));
        Component pavilion = new Component(
                "MASS_MAIN", "pavilion", new Vec3i(11, 0, 3),
                new Dimensions(6, 6, 5), List.of("pavilion"),
                Map.of("component_id", "pavilion_water", "building_role", "pavilion"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(hall, pavilion))
                .build();
        LlmPlan repaired = MassFootprintSeparationRepair.apply(plan);
        Component moved = repaired.components().stream()
                .filter(c -> "pavilion_water".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        Component stay = repaired.components().stream()
                .filter(c -> "hall_main".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(hall.relativePosition(), stay.relativePosition());
        assertTrue(
                moved.relativePosition().x() >= stay.relativePosition().x() + stay.dimensions().width()
                        || moved.relativePosition().z() >= stay.relativePosition().z() + stay.dimensions().depth()
                        || moved.relativePosition().x() + moved.dimensions().width()
                        <= stay.relativePosition().x()
                        || moved.relativePosition().z() + moved.dimensions().depth()
                        <= stay.relativePosition().z(),
                () -> "still overlapping: pavilion=" + moved.relativePosition()
                        + " hall=" + stay.relativePosition());
    }

    @Test
    void plinthFoundationCentersUnderMass() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(14, 0, 0),
                new Dimensions(14, 20, 8), List.of(),
                Map.of("component_id", "mass_main_hall", "anchor_mode", "min_corner"));
        Component foundation = new Component(
                "FOUNDATION", "main", new Vec3i(6, 0, -11),
                new Dimensions(16, 22, 1), List.of("石台基", "阶条石"),
                Map.of("component_id", "found_main", "host_id", "mass_main_hall",
                        "anchor_mode", "min_corner", "plinth_height", 1));
        Component aligned = ComponentSatelliteAligner.alignFoundationToMassForTest(foundation, mass);
        assertEquals(13, aligned.relativePosition().x()); // 14 - (16-14)/2
        assertEquals(-1, aligned.relativePosition().y()); // top meets mass floor
        assertEquals(-1, aligned.relativePosition().z()); // 0 - (22-20)/2
        assertEquals(1, aligned.dimensions().height());
    }

    @Test
    void facedEntranceSnapsToResolvedMassWall() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(14, 20, 8), List.of(),
                Map.of("component_id", "mass_main_hall", "floor_height", 8, "hollow", true,
                        "anchor_mode", "min_corner", "wall_thickness", 1));
        Map<String, Object> doorParams = new HashMap<>();
        doorParams.put("host_id", "mass_main_hall");
        doorParams.put("facing", "EAST");
        doorParams.put("door_width", 2);
        doorParams.put("door_height", 4);
        doorParams.put("anchor_mode", "min_corner");
        Component door = new Component(
                "ENTRANCE", "main", new Vec3i(13, 0, 7),
                new Dimensions(1, 6, 5), List.of(), doorParams);
        LlmPlan plan = LlmPlanTestFixtures.builder().components(List.of(mass, door)).build();
        Component aligned = ComponentPlanCompiler.alignEntranceToMass(
                door, mass, plan, com.formacraft.common.llm.dto.GlobalConstraints.Facing.EAST);
        assertEquals("EAST", aligned.params().get("facing"));
        // Generator convention: facing EAST anchors on the min-X wall of the mass.
        assertEquals(0, aligned.relativePosition().x());
        assertEquals(0, aligned.relativePosition().y());
    }

    @Test
    void clearingDiagnosticsDropsStaleGap() {
        AssemblyCompileDiagnostics.set(new CapabilityGap(
                "E_PATCH_CONFLICT", "stale pavilion_water", "components", List.of()));
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        AssemblyCompileDiagnostics.clear();
        assertFalse(AssemblyCompileDiagnostics.hasGap());
    }

    @Test
    void unknownCourtyardHostRemapsAndClearsGap() {
        Component court = new Component(
                "COURTYARD", "main", new Vec3i(0, 0, 0),
                new Dimensions(20, 16, 1), List.of("courtyard"),
                Map.of("component_id", "garden_courtyard"));
        Component censer = new Component(
                "DECOR_DETAIL", "main", new Vec3i(0, 1, 0),
                new Dimensions(1, 1, 2), List.of("censer"),
                Map.of("component_id", "censer", "host_id", "courtyard"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(court, censer))
                .planStatus("capability_gap")
                .error("capability: host_id='courtyard' unknown for censer")
                .capabilityGap(new CapabilityGap(
                        "E_LLMPLAN_VALIDATION",
                        "capability: host_id='courtyard' unknown for censer",
                        "components",
                        List.of()))
                .build();
        LlmPlan repaired = HostIdRemapRepair.apply(plan);
        assertFalse(repaired.hasCapabilityGap());
        Component fixed = repaired.components().stream()
                .filter(c -> "censer".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("garden_courtyard", fixed.params().get("host_id"));
    }

    @Test
    void crownDroppedWhenRoofOwnsSameGateHost() {
        Component gate = new Component(
                "MASS_MAIN", "gate", new Vec3i(0, 0, 0),
                new Dimensions(12, 8, 10), List.of("gate"),
                Map.of("component_id", "gate_mass_01"));
        Component roof = new Component(
                "ROOF", "gate", new Vec3i(-1, 10, -1),
                new Dimensions(14, 10, 3), List.of(),
                Map.of("component_id", "gate_roof_01", "host_id", "gate_mass_01", "roof_type", "xuanshan"));
        Component crown = new Component(
                "CROWN", "gate", new Vec3i(0, 12, 0),
                new Dimensions(4, 4, 3), List.of(),
                Map.of("component_id", "component_14", "host_id", "gate_mass_01"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(gate, roof, crown))
                .build();
        LlmPlan repaired = ExplicitDesignConflictRepair.apply(plan);
        assertTrue(repaired.components().stream()
                .noneMatch(c -> c != null && "CROWN".equals(c.componentType())));
        assertTrue(repaired.components().stream()
                .anyMatch(c -> c != null && "ROOF".equals(c.componentType())));
    }

    @Test
    void gardenCompositionClampsFoundationAndForcesEnclosureWall() {
        Component hall = new Component(
                "MASS_MAIN", "main_hall_slot", new Vec3i(0, 0, 0),
                new Dimensions(8, 6, 8), List.of("main_hall"),
                Map.of("component_id", "main_hall"));
        Component foundation = new Component(
                "FOUNDATION", "main_hall_slot", new Vec3i(0, 0, 0),
                new Dimensions(8, 6, 8), List.of("plinth"),
                Map.of("component_id", "main_hall_foundation", "host_id", "main_hall"));
        Component wall = new Component(
                "WALL", "garden_slot", new Vec3i(0, 0, 0),
                new Dimensions(8, 6, 8), List.of("decorative_wall_doorway"),
                Map.of("component_id", "garden_wall"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("jiangnan_garden")
                .distinguishingFeatures(List.of("江南园林"))
                .components(List.of(hall, foundation, wall))
                .build();
        LlmPlan repaired = GardenCompositionRepair.apply(plan);
        Component found = repaired.components().stream()
                .filter(c -> "main_hall_foundation".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(1, found.dimensions().height());
        Component enclosure = repaired.components().stream()
                .filter(c -> "garden_wall".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("curtain_wall", enclosure.params().get("wall_type"));
        assertTrue(enclosure.dimensions().width() >= 24);
        assertTrue(enclosure.dimensions().height() <= 4);
    }

    @Test
    void horseHeadSteppedGableDoesNotInsetHuizhouWalls() {
        var mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(14, 14, 8),
                List.of("white_walls", "black_tile_roof", "two_storey", "horse_head_gable_walls"),
                Map.of("component_id", "mass_main_huizhou_01"));
        var semantic = new com.formacraft.common.compiler.semantic.SemanticComponent(
                "MASS_MAIN", null, mass, "Chinese_Vernacular_Huizhou");
        var layers = com.formacraft.common.generation.component.util.ResolvedFacadeLayers
                .resolve(semantic, 14, 14, 8);
        // Upper storey must keep full footprint — no 1-block setback that floats windows.
        assertEquals(14, layers[4].width);
        assertEquals(14, layers[4].depth);
        assertEquals(0, layers[4].xOffset);
        assertEquals(0, layers[4].zOffset);
    }
}
