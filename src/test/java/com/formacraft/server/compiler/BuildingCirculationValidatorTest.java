package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuildingCirculationValidatorTest {

    private static Component twoStoreyMass() {
        return new Component(
                "MASS_MAIN",
                "hall",
                new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 10),
                List.of(),
                Map.of(
                        "component_id", "hall",
                        "anchor_mode", "min_corner",
                        "wall_thickness", 1,
                        "floor_count", 2,
                        "floor_height", 4
                ));
    }

    /** Ground and upper floors at interior sample, no vertical link. */
    private static List<BlockPatch> disconnectedStoreyPatches() {
        List<BlockPatch> patches = new ArrayList<>();
        // Interior sample for wall_thickness=1 is (1, y, 4)
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 4, 4, "minecraft:oak_planks"));
        // Neighbor cells so standing isn't isolated awkwardly
        patches.add(new BlockPatch(BlockPatch.PLACE, 2, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 2, 4, 4, "minecraft:oak_planks"));
        return patches;
    }

    @Test
    void requiredFlagsDisconnectedStoreys() {
        Component mass = twoStoreyMass();
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(mass))
                .planMeta(Map.of("design_requirements", Map.of("circulation", "REQUIRED")))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(mass), disconnectedStoreyPatches(), Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_BUILDING_CIRCULATION_INCOMPLETE", gap.get().code());
    }

    @Test
    void omittedSkipsDisconnectedStoreys() {
        Component mass = twoStoreyMass();
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(mass))
                .planMeta(Map.of("design_requirements", Map.of("circulation", "OMITTED")))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(mass), disconnectedStoreyPatches(), Map.of());
        assertTrue(gap.isEmpty());
    }

    @Test
    void unspecifiedSkipsDisconnectedStoreys() {
        Component mass = twoStoreyMass();
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(mass))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(mass), disconnectedStoreyPatches(), Map.of());
        assertTrue(gap.isEmpty());
    }

    @Test
    void requiredWithEmptyPatchesIsDataMissing() {
        Component mass = twoStoreyMass();
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(mass))
                .planMeta(Map.of("design_requirements", Map.of("circulation", "REQUIRED")))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(mass), List.of(), Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_BUILDING_CIRCULATION_DATA_MISSING", gap.get().code());
    }

    @Test
    void requiredWithNoCirculationMassIsDataMissing() {
        Component plate = new Component(
                "MASS_SECONDARY",
                "p",
                new Vec3i(0, 0, 0),
                new Dimensions(4, 4, 1),
                List.of(),
                Map.of("extrude_mode", "plate", "plate_role", "bridge", "component_id", "deck"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(plate))
                .planMeta(Map.of("design_requirements", Map.of("circulation", "REQUIRED")))
                .build();
        var gap = BuildingCirculationValidator.validate(
                plan, List.of(plate), disconnectedStoreyPatches(), Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_BUILDING_CIRCULATION_DATA_MISSING", gap.get().code());
    }

    @Test
    void towerStoreyedMassIsCheckedUnderBuildingLocal() {
        Component tower = new Component(
                "TOWER",
                "spire",
                new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 10),
                List.of(),
                Map.of(
                        "component_id", "spire",
                        "anchor_mode", "min_corner",
                        "wall_thickness", 1,
                        "floor_count", 2,
                        "floor_height", 4
                ));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(tower))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_scope", "BUILDING_LOCAL"))
                .build();
        var gap = BuildingCirculationValidator.validate(
                plan, List.of(tower), disconnectedStoreyPatches(), Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_BUILDING_CIRCULATION_INCOMPLETE", gap.get().code());
    }

    @Test
    void connectedComplexFlagsDisconnectedGrounds() {
        Component a = new Component(
                "MASS_MAIN", "a", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "a", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        Component b = new Component(
                "MASS_MAIN", "b", new Vec3i(20, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "b", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        // alpha interior (1,0,4); beta at x=20 → interior (21,0,4)
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 21, 0, 4, "minecraft:oak_planks"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(a, b))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_scope", "CONNECTED_COMPLEX"))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(a, b), patches, Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_BUILDING_CIRCULATION_INCOMPLETE", gap.get().code());
        assertTrue(gap.get().message().contains("CONNECTED_COMPLEX")
                || gap.get().message().contains("walkable complex"));
    }

    @Test
    void invalidCirculationScopeHardFails() {
        Component mass = twoStoreyMass();
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(mass))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_scope", "CONNECTED_COMPLES"))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(mass), disconnectedStoreyPatches(), Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_CIRCULATION_SCOPE_INVALID", gap.get().code());
    }

    @Test
    void attachedAerialWingSkipsOwnGroundUnderLocal() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        // Elevated wing: layout ground Y=0 has no slab; wing floor only at y=4
        Component wing = new Component(
                "MASS_WING", "wing", new Vec3i(12, 4, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "wing", "host_id", "hall", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        // Hall ground (1,0,4) → lateral +1Y stairs → wing interior (13,4,4)
        for (int x = 1; x <= 8; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 4, "minecraft:oak_planks"));
        }
        int[][] steps = {{9, 0}, {10, 1}, {11, 2}, {12, 3}, {13, 4}};
        for (int[] s : steps) {
            patches.add(new BlockPatch(BlockPatch.PLACE, s[0], s[1], 4, "minecraft:oak_planks"));
        }
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(hall, wing))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_scope", "BUILDING_LOCAL"))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(hall, wing), patches, Map.of());
        assertTrue(gap.isEmpty(), () -> gap.map(Object::toString).orElse(""));
    }

    @Test
    void attachedAerialWingWithoutPathFails() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        Component wing = new Component(
                "MASS_WING", "wing", new Vec3i(12, 4, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "wing", "host_id", "hall", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 2, 0, 4, "minecraft:oak_planks"));
        // Wing floor present but disconnected from hall
        patches.add(new BlockPatch(BlockPatch.PLACE, 13, 4, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 14, 4, 4, "minecraft:oak_planks"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(hall, wing))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_scope", "BUILDING_LOCAL"))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(hall, wing), patches, Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_BUILDING_CIRCULATION_INCOMPLETE", gap.get().code());
        assertTrue(gap.get().message().toLowerCase().contains("attached")
                || gap.get().message().contains("wing"));
    }

    @Test
    void hostIdCycleHardFails() {
        Component a = new Component(
                "MASS_MAIN", "a", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "alpha", "host_id", "beta", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        Component b = new Component(
                "MASS_MAIN", "b", new Vec3i(16, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "beta", "host_id", "alpha", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 17, 0, 4, "minecraft:oak_planks"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(a, b))
                .planMeta(Map.of("design_requirements", Map.of("circulation", "REQUIRED")))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(a, b), patches, Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_CIRCULATION_HOST_CYCLE", gap.get().code());
    }

    @Test
    void attachedWingOwnGroundButDisconnectedFails() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        Component wing = new Component(
                "MASS_WING", "wing", new Vec3i(20, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "wing", "host_id", "hall", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        // Both have standable grounds; no corridor between them
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 2, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 21, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 22, 0, 4, "minecraft:oak_planks"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(hall, wing))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_scope", "BUILDING_LOCAL"))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(hall, wing), patches, Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_BUILDING_CIRCULATION_INCOMPLETE", gap.get().code());
        assertTrue(gap.get().message().toLowerCase().contains("attached")
                || gap.get().message().contains("wing"));
    }

    @Test
    void attachedWingOwnGroundConnectedPasses() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        Component wing = new Component(
                "MASS_WING", "wing", new Vec3i(20, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "wing", "host_id", "hall", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        for (int x = 1; x <= 22; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 4, "minecraft:oak_planks"));
        }
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(hall, wing))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_scope", "BUILDING_LOCAL"))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(hall, wing), patches, Map.of());
        assertTrue(gap.isEmpty(), () -> gap.map(Object::toString).orElse(""));
    }

    @Test
    void hostIdTriangleCycleHardFails() {
        Component a = new Component(
                "MASS_MAIN", "a", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "a", "host_id", "b", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        Component b = new Component(
                "MASS_MAIN", "b", new Vec3i(16, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "b", "host_id", "c", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        Component c = new Component(
                "MASS_MAIN", "c", new Vec3i(32, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "c", "host_id", "a", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 17, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 33, 0, 4, "minecraft:oak_planks"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(a, b, c))
                .planMeta(Map.of("design_requirements", Map.of("circulation", "REQUIRED")))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(a, b, c), patches, Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_CIRCULATION_HOST_CYCLE", gap.get().code());
    }

    @Test
    void hostIdSelfReferenceIsUnresolved() {
        Component a = new Component(
                "MASS_MAIN", "a", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "a", "host_id", "a", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(a))
                .planMeta(Map.of("design_requirements", Map.of("circulation", "REQUIRED")))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(a), patches, Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_BUILDING_CIRCULATION_INCOMPLETE", gap.get().code());
        assertTrue(gap.get().message().toLowerCase().contains("host"));
    }

    @Test
    void hostIdChainConnectedPasses() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        Component mid = new Component(
                "MASS_WING", "mid", new Vec3i(12, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "mid", "host_id", "hall", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        Component tip = new Component(
                "MASS_WING", "tip", new Vec3i(24, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "tip", "host_id", "mid", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        for (int x = 1; x <= 26; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 4, "minecraft:oak_planks"));
        }
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(hall, mid, tip))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_scope", "BUILDING_LOCAL"))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(hall, mid, tip), patches, Map.of());
        assertTrue(gap.isEmpty(), () -> gap.map(Object::toString).orElse(""));
    }

    @Test
    void hostIdChainMidUnresolvedFails() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        Component tip = new Component(
                "MASS_WING", "tip", new Vec3i(24, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "tip", "host_id", "missing_mid", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 25, 0, 4, "minecraft:oak_planks"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(hall, tip))
                .planMeta(Map.of("design_requirements", Map.of("circulation", "REQUIRED")))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(hall, tip), patches, Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_BUILDING_CIRCULATION_INCOMPLETE", gap.get().code());
        assertTrue(gap.get().message().toLowerCase().contains("host"));
    }

    @Test
    void aerialWingWithoutHostRequiresOwnGround() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        Component wing = new Component(
                "MASS_WING", "wing", new Vec3i(12, 4, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "wing", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 13, 4, 4, "minecraft:oak_planks"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(hall, wing))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_scope", "BUILDING_LOCAL"))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(hall, wing), patches, Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_BUILDING_CIRCULATION_INCOMPLETE", gap.get().code());
        assertTrue(gap.get().message().contains("wing") || gap.get().message().contains("ground"));
    }

    @Test
    void unresolvedHostIdIsIncomplete() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        Component wing = new Component(
                "MASS_WING", "wing", new Vec3i(12, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "wing", "host_id", "missing", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 13, 0, 4, "minecraft:oak_planks"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(hall, wing))
                .planMeta(Map.of("design_requirements", Map.of("circulation", "REQUIRED")))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(hall, wing), patches, Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_BUILDING_CIRCULATION_INCOMPLETE", gap.get().code());
        assertTrue(gap.get().message().toLowerCase().contains("host"));
    }

    @Test
    void directHostLinkBlockedWhenParentChildDisconnected() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        Component mid = new Component(
                "MASS_WING", "mid", new Vec3i(12, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "mid", "host_id", "hall", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        Component tip = new Component(
                "MASS_WING", "tip", new Vec3i(28, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "tip", "host_id", "mid", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        // hall ↔ mid only; tip has its own floor but no path from mid (or hall)
        for (int x = 1; x <= 14; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 4, "minecraft:oak_planks"));
        }
        patches.add(new BlockPatch(BlockPatch.PLACE, 29, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 30, 0, 4, "minecraft:oak_planks"));
        LlmPlan directPlan = LlmPlanTestFixtures.builder()
                .components(List.of(hall, mid, tip))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_scope", "BUILDING_LOCAL",
                        "circulation_host_links", "DIRECT"))
                .build();
        var gap = BuildingCirculationValidator.validate(directPlan, List.of(hall, mid, tip), patches, Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_CIRCULATION_HOST_LINK_BLOCKED", gap.get().code());
    }

    @Test
    void dualStandablePocketsOnlyOneReachableFails() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        Component wing = new Component(
                "MASS_WING", "wing", new Vec3i(16, 0, 0), new Dimensions(10, 8, 8), List.of(),
                Map.of("component_id", "wing", "host_id", "hall", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        for (int x = 1; x <= 18; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 4, "minecraft:oak_planks"));
        }
        // Near pocket reachable from corridor; far pocket isolated (gap at x=20-21)
        patches.add(new BlockPatch(BlockPatch.PLACE, 17, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 18, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 19, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 22, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 23, 0, 4, "minecraft:oak_planks"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(hall, wing))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_scope", "BUILDING_LOCAL"))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(hall, wing), patches, Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_BUILDING_CIRCULATION_INCOMPLETE", gap.get().code());
    }

    @Test
    void invalidCirculationHostLinksHardFails() {
        Component mass = twoStoreyMass();
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(mass))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_host_links", "FOO"))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(mass), disconnectedStoreyPatches(), Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_CIRCULATION_HOST_LINKS_INVALID", gap.get().code());
    }

    @Test
    void nonzeroSlotAnchorRegionScanFindsWorldFloors() {
        // Local geometry at origin; wing world pose comes only from slot.anchor=(5,2,3)
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        Component wing = new Component(
                "MASS_WING", "wing", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "wing", "host_id", "hall", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        // Hall ground corridor (lateral — stacked Y on same XZ would block headroom)
        for (int x = 1; x <= 4; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 4, "minecraft:oak_planks"));
        }
        // Lateral stairs + Z approach into wing world: local (1,0,4)+(5,2,3)=(6,2,7)
        int[][] steps = {
                {4, 0, 4}, {5, 1, 4}, {6, 2, 4},
                {6, 2, 5}, {6, 2, 6}, {6, 2, 7}, {7, 2, 7}
        };
        for (int[] s : steps) {
            patches.add(new BlockPatch(BlockPatch.PLACE, s[0], s[1], s[2], "minecraft:oak_planks"));
        }
        Map<String, com.formacraft.common.llm.dto.Slot> slots = Map.of(
                "hall", new com.formacraft.common.llm.dto.Slot(
                        "hall", new Vec3i(0, 0, 0), null, null, null, null),
                "wing", new com.formacraft.common.llm.dto.Slot(
                        "wing", new Vec3i(5, 2, 3), null, null, null, null));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(hall, wing))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_scope", "BUILDING_LOCAL"))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(hall, wing), patches, slots);
        assertTrue(gap.isEmpty(), () -> gap.map(Object::toString).orElse(""));
    }

    @Test
    void nonzeroSlotFailsWhenFloorOnlyAtUnshiftedCoords() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        Component wing = new Component(
                "MASS_WING", "wing", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "wing", "host_id", "hall", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 2, 0, 4, "minecraft:oak_planks"));
        // Wing floor only at unshifted local interior — wrong once slot=(5,2,3) is applied
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 2, 0, 4, "minecraft:oak_planks"));
        Map<String, com.formacraft.common.llm.dto.Slot> slots = Map.of(
                "hall", new com.formacraft.common.llm.dto.Slot(
                        "hall", new Vec3i(0, 0, 0), null, null, null, null),
                "wing", new com.formacraft.common.llm.dto.Slot(
                        "wing", new Vec3i(5, 2, 3), null, null, null, null));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(hall, wing))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_scope", "BUILDING_LOCAL"))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(hall, wing), patches, slots);
        assertTrue(gap.isPresent());
        assertEquals("E_BUILDING_CIRCULATION_INCOMPLETE", gap.get().code());
    }

    @Test
    void oversizedStoreyRegionScanReturnsScanLimit() {
        Component tower = new Component(
                "MASS_MAIN", "tower", new Vec3i(0, 0, 0), new Dimensions(80, 80, 12), List.of(),
                Map.of("component_id", "tower", "wall_thickness", 1, "floor_count", 2, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 0, 40, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 4, 40, "minecraft:oak_planks"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(tower))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_scope", "BUILDING_LOCAL"))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(tower), patches, Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_CIRCULATION_REGION_SCAN_LIMIT", gap.get().code());
    }

    @Test
    void traverseSplitRegionsOnlyOneReachableIsIncomplete() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        Component wing = new Component(
                "MASS_WING", "wing", new Vec3i(12, 0, 0), new Dimensions(12, 8, 8), List.of(),
                Map.of("component_id", "wing", "host_id", "hall", "wall_thickness", 1,
                        "floor_count", 1, "floor_height", 4, "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        // Path hall → near wing pocket only; far pocket adjacent in footprint but isolated
        for (int x = 1; x <= 14; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 4, "minecraft:oak_planks"));
        }
        patches.add(new BlockPatch(BlockPatch.PLACE, 13, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 14, 0, 4, "minecraft:oak_planks"));
        // Far pocket: standable but not edge-connected (gap) — two region reps, entry hits one
        patches.add(new BlockPatch(BlockPatch.PLACE, 20, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 21, 0, 4, "minecraft:oak_planks"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(hall, wing))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_scope", "BUILDING_LOCAL"))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(hall, wing), patches, Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_BUILDING_CIRCULATION_INCOMPLETE", gap.get().code());
    }

    @Test
    void singleStoreyIndependentMassDisconnectedRegionsFails() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(14, 8, 8), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        // Left room (includes interior sample ~1,0,4)
        for (int x = 1; x <= 4; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 4, "minecraft:oak_planks"));
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 3, "minecraft:oak_planks"));
        }
        // Gap / partition at x=5..8 — right room unreachable
        for (int x = 9; x <= 12; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 4, "minecraft:oak_planks"));
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 3, "minecraft:oak_planks"));
        }
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(hall))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_scope", "BUILDING_LOCAL"))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(hall), patches, Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_BUILDING_CIRCULATION_INCOMPLETE", gap.get().code());
    }

    @Test
    void singleStoreyIndependentMassConnectedRegionsPass() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0), new Dimensions(14, 8, 8), List.of(),
                Map.of("component_id", "hall", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        for (int x = 1; x <= 12; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 4, "minecraft:oak_planks"));
        }
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(hall))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_scope", "BUILDING_LOCAL"))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(hall), patches, Map.of());
        assertTrue(gap.isEmpty(), () -> gap.map(Object::toString).orElse(""));
    }

    @Test
    void connectedComplexMultistoreyGroundPartitionFails() {
        // Two 2-storey independents joined at L2; alpha ground has an isolated pocket.
        Component alpha = new Component(
                "MASS_MAIN", "alpha", new Vec3i(0, 0, 0), new Dimensions(14, 8, 10), List.of(),
                Map.of("component_id", "alpha", "wall_thickness", 1, "floor_count", 2, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        Component beta = new Component(
                "MASS_MAIN", "beta", new Vec3i(24, 0, 0), new Dimensions(10, 8, 10), List.of(),
                Map.of("component_id", "beta", "wall_thickness", 1, "floor_count", 2, "floor_height", 4,
                        "anchor_mode", "min_corner"));
        List<BlockPatch> patches = new ArrayList<>();
        // Alpha ground left room (entry + stair) — gap x=5..8 — right pocket unreachable
        for (int x = 1; x <= 4; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 3, "minecraft:oak_planks"));
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 4, "minecraft:oak_planks"));
        }
        for (int x = 9; x <= 12; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 3, "minecraft:oak_planks"));
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 4, "minecraft:oak_planks"));
        }
        // Alpha stairs left room → L2
        int[][] alphaSteps = {{3, 0}, {4, 1}, {5, 2}, {6, 3}, {7, 4}};
        for (int[] s : alphaSteps) {
            patches.add(new BlockPatch(BlockPatch.PLACE, s[0], s[1], 4, "minecraft:oak_planks"));
        }
        // Alpha L2 floor (connected) + bridge to beta L2
        for (int x = 1; x <= 12; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 4, 3, "minecraft:oak_planks"));
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 4, 4, "minecraft:oak_planks"));
        }
        for (int x = 13; x <= 24; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 4, 4, "minecraft:oak_planks"));
        }
        // Beta L2 + ground + stairs
        for (int x = 25; x <= 32; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 4, 3, "minecraft:oak_planks"));
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 4, 4, "minecraft:oak_planks"));
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 3, "minecraft:oak_planks"));
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 4, "minecraft:oak_planks"));
        }
        int[][] betaSteps = {{27, 0}, {28, 1}, {29, 2}, {30, 3}, {31, 4}};
        for (int[] s : betaSteps) {
            patches.add(new BlockPatch(BlockPatch.PLACE, s[0], s[1], 4, "minecraft:oak_planks"));
        }
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(alpha, beta))
                .planMeta(Map.of(
                        "design_requirements", Map.of("circulation", "REQUIRED"),
                        "circulation_scope", "CONNECTED_COMPLEX"))
                .build();
        var gap = BuildingCirculationValidator.validate(plan, List.of(alpha, beta), patches, Map.of());
        assertTrue(gap.isPresent());
        assertEquals("E_BUILDING_CIRCULATION_INCOMPLETE", gap.get().code());
    }
}
