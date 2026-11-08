package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.server.assembly.AssemblyCirculationOps;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureAccessoryRemapRepairTest {

    @Test
    void remapsHaidenPlinthStructureToFoundation() {
        Component main = new Component(
                "STRUCTURE", "main", new Vec3i(0, 0, 0),
                new Dimensions(14, 12, 8), List.of("typology:japanese_haiden"),
                Map.of("component_id", "haiden_main", "typology_id", "japanese_haiden"));
        Component plinth = new Component(
                "STRUCTURE", "main", new Vec3i(-1, -1, -1),
                new Dimensions(16, 14, 1), List.of("台基"),
                Map.of("component_id", "haiden_plinth", "host_id", "haiden_main"));
        LlmPlan repaired = StructureAccessoryRemapRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(main, plinth))
                .build());
        assertEquals("STRUCTURE", repaired.components().get(0).componentType());
        assertEquals("FOUNDATION", repaired.components().get(1).componentType());
        assertEquals("haiden_plinth", repaired.components().get(1).params().get("component_id"));
    }

    @Test
    void remapsApproachStepsToStairSystemNotFoundation() {
        Component steps = new Component(
                "STRUCTURE", "main", new Vec3i(4, 0, 12),
                new Dimensions(4, 6, 3), List.of(),
                Map.of("component_id", "haiden_steps"));
        LlmPlan repaired = StructureAccessoryRemapRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(steps))
                .build());
        Component out = repaired.components().getFirst();
        assertEquals("STAIR_SYSTEM", out.componentType());
        assertFalse("FOUNDATION".equalsIgnoreCase(out.componentType()));
        assertTrue(out.features().stream().anyMatch(f -> f != null && f.startsWith("stair:")));
        assertNotNull(out.params().get("from"));
        assertNotNull(out.params().get("to"));
        assertNull(AssemblyCirculationOps.flightError(out.params().get("from"), out.params().get("to")));
    }

    @Test
    void fourByFourByThreeStoneBricksHasLawfulFlightAndStairsBlock() {
        Component steps = new Component(
                "STRUCTURE", "main", new Vec3i(4, 0, 12),
                new Dimensions(4, 4, 3), List.of(),
                Map.of("component_id", "haiden_steps", "material", "minecraft:stone_bricks",
                        "facing", "SOUTH"));
        LlmPlan repaired = StructureAccessoryRemapRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(steps))
                .build());
        Component out = repaired.components().getFirst();
        assertEquals("STAIR_SYSTEM", out.componentType());
        assertEquals("minecraft:stone_brick_stairs", out.params().get("stairs"));
        assertTrue(String.valueOf(out.params().get("stairs")).endsWith("_stairs"));
        assertNull(AssemblyCirculationOps.flightError(out.params().get("from"), out.params().get("to")),
                "4×4×3 must not fail rise>run");
        @SuppressWarnings("unchecked")
        Map<String, Object> from = (Map<String, Object>) out.params().get("from");
        @SuppressWarnings("unchecked")
        Map<String, Object> to = (Map<String, Object>) out.params().get("to");
        int rise = ((Number) to.get("y")).intValue() - ((Number) from.get("y")).intValue();
        int run = Math.max(
                Math.abs(((Number) to.get("x")).intValue() - ((Number) from.get("x")).intValue()),
                Math.abs(((Number) to.get("z")).intValue() - ((Number) from.get("z")).intValue()));
        assertTrue(run >= rise, "run=" + run + " rise=" + rise);
    }

    @Test
    void approachFacingFollowsHost() {
        Component main = new Component(
                "STRUCTURE", "main", new Vec3i(0, 0, 0),
                new Dimensions(14, 12, 8), List.of("typology:japanese_haiden"),
                Map.of("component_id", "haiden_main", "typology_id", "japanese_haiden",
                        "facing", "NORTH"));
        Component steps = new Component(
                "STRUCTURE", "main", new Vec3i(4, 0, -6),
                new Dimensions(4, 6, 3), List.of(),
                Map.of("component_id", "haiden_steps", "host_id", "haiden_main"));
        LlmPlan repaired = StructureAccessoryRemapRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(main, steps))
                .build());
        Component out = repaired.components().get(1);
        assertEquals("NORTH", out.params().get("facing"));
        assertTrue(out.features().stream().anyMatch(f ->
                f != null && f.contains("north_high_end")));
    }

    @Test
    void interiorHostedFlightIsNotRewrittenAsExteriorApproach() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 10), List.of(),
                Map.of("component_id", "hall", "anchor_mode", "min_corner", "wall_thickness", 1));
        Component stair = new Component(
                "STRUCTURE", "hall", new Vec3i(3, 0, 3),
                new Dimensions(6, 3, 6), List.of("stair:straight_single_run"),
                Map.of("component_id", "hall_stair", "host_id", "hall",
                        "from", Map.of("x", 0, "y", 0, "z", 0),
                        "to", Map.of("x", 4, "y", 4, "z", 0),
                        "width", 2, "landing_length", 2,
                        "stairs", "minecraft:oak_stairs", "floor", "minecraft:oak_planks"));
        LlmPlan repaired = StructureAccessoryRemapRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(hall, stair))
                .build());
        Component out = repaired.components().get(1);
        assertEquals("STAIR_SYSTEM", out.componentType());
        assertEquals(new Vec3i(3, 0, 3), out.relativePosition());
        assertEquals(Map.of("x", 0, "y", 0, "z", 0), out.params().get("from"));
        assertEquals(Map.of("x", 4, "y", 4, "z", 0), out.params().get("to"));
        assertFalse(Boolean.TRUE.equals(out.params().get("exterior")));
        assertFalse(Boolean.TRUE.equals(out.params().get("approach_stair")));
        assertFalse(Boolean.TRUE.equals(out.params().get("compiler_approach_remap")));
    }

    @Test
    void invalidApproachEndpointSetsGapWithoutThrowing() {
        com.formacraft.server.assembly.AssemblyCompileDiagnostics.clear();
        Component host = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(14, 12, 8), List.of(),
                Map.of("component_id", "hall_main", "facing", "SOUTH", "anchor_mode", "min_corner"));
        Component bad = new Component(
                "STAIR_SYSTEM", "main", new Vec3i(4, -1, 12),
                new Dimensions(3, 4, 3), List.of("stair:straight_single_run"),
                Map.of("component_id", "bad_approach", "host_id", "hall_main",
                        "exterior", true, "approach_stair", true, "facing", "SOUTH",
                        "from", Map.of("x", "oops", "y", 0, "z", 0),
                        "to", Map.of("x", 1, "y", 2, "z", 3)));
        Component good = new Component(
                "STAIR_SYSTEM", "main", new Vec3i(4, -1, 12),
                new Dimensions(3, 4, 3), List.of("stair:straight_single_run"),
                Map.of("component_id", "good_approach", "host_id", "hall_main",
                        "exterior", true, "approach_stair", true, "facing", "SOUTH",
                        "from", Map.of("x", 1, "y", 0, "z", 0),
                        "to", Map.of("x", 1, "y", 2, "z", 3)));
        java.util.ArrayList<Component> parts = new java.util.ArrayList<>(List.of(host, bad, good));
        StructureAccessoryRemapRepair.resnapApproachStairs(parts);
        assertEquals("E_STAIR_COMPONENT_INVALID",
                com.formacraft.server.assembly.AssemblyCompileDiagnostics.get().code());
        assertTrue(String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get().message())
                .contains("bad_approach"));
        assertEquals(3, parts.size(), "valid siblings must remain after invalid approach stair");
    }

    @Test
    void leavesColumnsAsStructure() {
        Component pillars = new Component(
                "STRUCTURE", "main", new Vec3i(0, 0, 0),
                new Dimensions(2, 2, 4), List.of(),
                Map.of("component_id", "haiden_kouhai_pillars"));
        LlmPlan repaired = StructureAccessoryRemapRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(pillars))
                .build());
        assertEquals("STRUCTURE", repaired.components().getFirst().componentType());
    }

    @Test
    void resolveStairsBlockMapsStoneBricks() {
        assertEquals("minecraft:stone_brick_stairs",
                StructureAccessoryRemapRepair.resolveStairsBlock("minecraft:stone_bricks"));
        assertEquals("minecraft:oak_stairs",
                StructureAccessoryRemapRepair.resolveStairsBlock("minecraft:oak_planks"));
    }
}
