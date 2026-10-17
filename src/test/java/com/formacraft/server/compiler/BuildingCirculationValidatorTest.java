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
}
