package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
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

    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
    }

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
    void deepGateWithThreeDoorwaysFeaturePassesWithoutBaysParam() {
        // Live log regression: gate_body 18×22 with three_doorways / 3 ENTRANCE, no bays=.
        Component body = new Component(
                "MASS_MAIN", "gate", new Vec3i(0, 0, 0),
                new Dimensions(18, 22, 12),
                List.of("central_bay", "three_doorways", "pillars"),
                Map.of("component_id", "gate_body"));
        Component roof = new Component(
                "ROOF", "gate", new Vec3i(-2, 12, -2),
                new Dimensions(22, 26, 3), List.of("xieshan_roof"),
                Map.of("component_id", "roof_main", "host_id", "gate_body", "roof_type", "xieshan"));
        Component doorC = new Component(
                "ENTRANCE", "gate", new Vec3i(7, 0, 0),
                new Dimensions(4, 1, 6), List.of("double_door"),
                Map.of("component_id", "entrance_central", "host_id", "gate_body"));
        Component doorL = new Component(
                "ENTRANCE", "gate", new Vec3i(2, 0, 0),
                new Dimensions(3, 1, 5), List.of("single_door"),
                Map.of("component_id", "entrance_left", "host_id", "gate_body"));
        Component doorR = new Component(
                "ENTRANCE", "gate", new Vec3i(13, 0, 0),
                new Dimensions(3, 1, 5), List.of("single_door"),
                Map.of("component_id", "entrance_right", "host_id", "gate_body"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(body, roof, doorC, doorL, doorR))
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
    void flowerWallHostIsHostableForLeakyWindows() {
        Component wall = new Component(
                "WALL", "garden", new Vec3i(0, 0, 0),
                new Dimensions(6, 10, 3), List.of("leaky_window_openings"),
                Map.of("component_id", "flower_wall"));
        Component decor = new Component(
                "DECOR_DETAIL", "garden", new Vec3i(0, 1, 0),
                new Dimensions(6, 10, 1), List.of("leaky_window_grid"),
                Map.of("component_id", "leaky_windows", "host_id", "flower_wall"));
        Component hall = new Component(
                "MASS_MAIN", "garden", new Vec3i(10, 0, 0),
                new Dimensions(13, 10, 5), List.of("main_hall"),
                Map.of("component_id", "hall_main"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("jiangnan_garden")
                .components(List.of(wall, decor, hall))
                .planStatus("capability_gap")
                .error("capability: host 'flower_wall' type=WALL is not hostable")
                .capabilityGap(new CapabilityGap(
                        "E_LLMPLAN_VALIDATION",
                        "capability: host 'flower_wall' type=WALL is not hostable",
                        "plan",
                        List.of()))
                .build();
        LlmPlan repaired = HostIdRemapRepair.apply(plan);
        assertFalse(repaired.hasCapabilityGap());
        Component fixed = repaired.components().stream()
                .filter(c -> "leaky_windows".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("flower_wall", fixed.params().get("host_id"));
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
    void plinthAndStepsStayUnderHall() {
        Component main = new Component(
                "STRUCTURE", "main", new Vec3i(0, 1, 0),
                new Dimensions(14, 12, 8), List.of(),
                Map.of("component_id", "haiden_main", "typology_id", "japanese_haiden",
                        "anchor_mode", "min_corner"));
        Component plinth = new Component(
                "STRUCTURE", "main", new Vec3i(-1, 0, -1),
                new Dimensions(16, 14, 1), List.of("台基"),
                Map.of("component_id", "haiden_plinth", "anchor_mode", "min_corner"));
        Component steps = new Component(
                "STRUCTURE", "main", new Vec3i(4, 0, 12),
                new Dimensions(4, 4, 1), List.of(),
                Map.of("component_id", "haiden_steps", "anchor_mode", "min_corner"));
        LlmPlan repaired = MassFootprintSeparationRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(main, plinth, steps))
                .build());
        assertEquals(plinth.relativePosition(), repaired.components().get(1).relativePosition());
        assertEquals(steps.relativePosition(), repaired.components().get(2).relativePosition());
    }

    @Test
    void stackedTiersStayNestedOnMainMass() {
        // LLM stepped towers nest smaller tiers above the base — must not flatten to ground.
        Component base = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(24, 24, 10), List.of(),
                Map.of("component_id", "res_main", "anchor_mode", "min_corner"));
        Component tier = new Component(
                "MASS_SECONDARY", "main", new Vec3i(4, 5, 4),
                new Dimensions(16, 16, 8), List.of(),
                Map.of("component_id", "tier_l2", "host_id", "res_main",
                        "tier_index", 1, "anchor_mode", "min_corner"));
        LlmPlan repaired = MassFootprintSeparationRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(base, tier))
                .build());
        Component kept = repaired.components().stream()
                .filter(c -> "tier_l2".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(tier.relativePosition(), kept.relativePosition(),
                "stacked tier must not be nudged off the main footprint");
    }

    @Test
    void overlappingPavilionIsNudgedOffHallFootprint() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, -8),
                new Dimensions(16, 12, 8), List.of("main_hall"),
                Map.of("component_id", "hall_main", "building_role", "hall_main",
                        "anchor_mode", "min_corner"));
        Component pavilion = new Component(
                "MASS_MAIN", "pavilion", new Vec3i(11, 0, 3),
                new Dimensions(6, 6, 5), List.of("pavilion"),
                Map.of("component_id", "pavilion_water", "building_role", "pavilion",
                        "anchor_mode", "min_corner"));
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

    @Test
    void differentSlotsWithSharedFrameMustSeparate() {
        // Distinct slot labels in one plan frame still collide in world/plan space.
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, -8),
                new Dimensions(16, 12, 8), List.of("main_hall"),
                Map.of("component_id", "hall_main", "building_role", "hall_main",
                        "anchor_mode", "min_corner"));
        Component pavilion = new Component(
                "MASS_MAIN", "pavilion", new Vec3i(11, 0, 3),
                new Dimensions(6, 6, 5), List.of("pavilion"),
                Map.of("component_id", "pavilion_water", "building_role", "pavilion",
                        "anchor_mode", "min_corner"));
        LlmPlan repaired = MassFootprintSeparationRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(hall, pavilion))
                .build());
        Component moved = repaired.components().stream()
                .filter(c -> "pavilion_water".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        Component stay = repaired.components().stream()
                .filter(c -> "hall_main".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertFalse(
                moved.relativePosition().x() < stay.relativePosition().x() + stay.dimensions().width()
                        && moved.relativePosition().x() + moved.dimensions().width() > stay.relativePosition().x()
                        && moved.relativePosition().z() < stay.relativePosition().z() + stay.dimensions().depth()
                        && moved.relativePosition().z() + moved.dimensions().depth() > stay.relativePosition().z(),
                () -> "cross-slot footprints still overlap: pavilion=" + moved.relativePosition()
                        + " hall=" + stay.relativePosition());
    }

    @Test
    void foreignHostedRoofMustNotMove() {
        Component hall = new Component(
                "MASS_MAIN", "shared", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of(),
                Map.of("component_id", "hall_main", "building_role", "hall_main",
                        "anchor_mode", "min_corner"));
        Component pavilion = new Component(
                "MASS_MAIN", "shared", new Vec3i(8, 0, 2),
                new Dimensions(6, 6, 5), List.of(),
                Map.of("component_id", "pavilion_a", "building_role", "pavilion",
                        "anchor_mode", "min_corner"));
        Component pavilionRoof = new Component(
                "ROOF", "shared", new Vec3i(7, 5, 1),
                new Dimensions(8, 8, 3), List.of(),
                Map.of("component_id", "pavilion_a_roof", "host_id", "pavilion_a",
                        "anchor_mode", "min_corner"));
        Vec3i roofBefore = pavilionRoof.relativePosition();
        LlmPlan repaired = MassFootprintSeparationRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(hall, pavilion, pavilionRoof))
                .build());
        Component movedPavilion = repaired.components().stream()
                .filter(c -> "pavilion_a".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        Component roof = repaired.components().stream()
                .filter(c -> "pavilion_a_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        // Pavilion should move off the hall; its explicitly hosted roof follows pavilion, not hall.
        assertNotEquals(pavilion.relativePosition(), movedPavilion.relativePosition());
        int dx = movedPavilion.relativePosition().x() - pavilion.relativePosition().x();
        int dz = movedPavilion.relativePosition().z() - pavilion.relativePosition().z();
        assertEquals(roofBefore.x() + dx, roof.relativePosition().x());
        assertEquals(roofBefore.z() + dz, roof.relativePosition().z());
    }

    @Test
    void sideHallsSeparateOnOppositeSidesAndLiftSatellites() {
        Component hall = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 2, 0),
                new Dimensions(16, 12, 8), List.of(),
                Map.of("component_id", "main_hall", "building_role", "hall_main",
                        "anchor_mode", "min_corner"));
        Component north = new Component(
                "MASS_SECONDARY", "north", new Vec3i(4, -3, 2),
                new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "north_side_hall", "building_role", "side_hall",
                        "anchor_mode", "min_corner"));
        Component northRoof = new Component(
                "ROOF", "north", new Vec3i(3, 3, 1),
                new Dimensions(10, 10, 3), List.of(),
                Map.of("component_id", "north_side_hall_roof", "host_id", "north_side_hall",
                        "anchor_mode", "min_corner"));
        Component south = new Component(
                "MASS_SECONDARY", "south", new Vec3i(4, -3, 2),
                new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "south_side_hall", "building_role", "side_hall",
                        "anchor_mode", "min_corner"));
        LlmPlan repaired = MassFootprintSeparationRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(hall, north, northRoof, south))
                .build());
        Component n = repaired.components().stream()
                .filter(c -> "north_side_hall".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        Component s = repaired.components().stream()
                .filter(c -> "south_side_hall".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        Component roof = repaired.components().stream()
                .filter(c -> "north_side_hall_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(2, n.relativePosition().y(), "side hall Y must flush to main hall floor");
        assertEquals(2, s.relativePosition().y());
        assertTrue(n.relativePosition().z() < hall.relativePosition().z()
                        || n.relativePosition().z() + n.dimensions().depth()
                        <= hall.relativePosition().z(),
                "north hall should leave the main footprint northward");
        assertTrue(s.relativePosition().z() >= hall.relativePosition().z() + hall.dimensions().depth()
                        || s.relativePosition().z() > n.relativePosition().z(),
                "south hall must not collapse onto the same cell as north");
        assertEquals(n.relativePosition().x() - 1, roof.relativePosition().x());
        assertEquals(n.relativePosition().z() - 1, roof.relativePosition().z());
    }
}
