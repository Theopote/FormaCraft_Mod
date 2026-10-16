package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WalkabilityGraphTest {

    @Test
    void standingRequiresSupportFeetAndHead() {
        Map<Vec3i, PlannedOccupancy.Cell> cells = new HashMap<>();
        cells.put(new Vec3i(0, 0, 0), PlannedOccupancy.Cell.SOLID);
        PlannedOccupancy occ = PlannedOccupancy.of(cells);
        assertTrue(WalkabilityGraph.canStand(occ, 0, 0, 0));

        cells.put(new Vec3i(0, 2, 0), PlannedOccupancy.Cell.SOLID);
        occ = PlannedOccupancy.of(cells);
        assertFalse(WalkabilityGraph.canStand(occ, 0, 0, 0), "head blocked");

        cells.remove(new Vec3i(0, 0, 0));
        cells.put(new Vec3i(0, 1, 0), PlannedOccupancy.Cell.PLANNED_AIR);
        occ = PlannedOccupancy.of(cells);
        assertFalse(WalkabilityGraph.canStand(occ, 0, 0, 0), "no solid support");
    }

    @Test
    void removeIsPlannedAirNotUntouched() {
        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 0, 1, "minecraft:stone"),
                new BlockPatch(BlockPatch.REMOVE, 1, 1, 1, "minecraft:air")
        );
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches);
        assertEquals(PlannedOccupancy.Cell.SOLID, occ.get(1, 0, 1));
        assertEquals(PlannedOccupancy.Cell.PLANNED_AIR, occ.get(1, 1, 1));
        assertEquals(PlannedOccupancy.Cell.UNTOUCHED, occ.get(9, 9, 9));
        assertTrue(occ.isBodyClear(1, 1, 1));
        assertFalse(occ.isSupport(1, 1, 1));
        assertFalse(occ.isSupport(9, 9, 9));
    }

    @Test
    void floatingAirPathCannotReach() {
        // Two platforms with only air between — no continuous floor.
        Map<Vec3i, PlannedOccupancy.Cell> cells = new HashMap<>();
        cells.put(new Vec3i(0, 0, 0), PlannedOccupancy.Cell.SOLID);
        cells.put(new Vec3i(5, 0, 0), PlannedOccupancy.Cell.SOLID);
        PlannedOccupancy occ = PlannedOccupancy.of(cells);
        assertFalse(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 0, 0), new Vec3i(5, 0, 0), 64));
    }

    @Test
    void continuousFloorPathReaches() {
        Map<Vec3i, PlannedOccupancy.Cell> cells = new HashMap<>();
        for (int x = 0; x <= 5; x++) {
            cells.put(new Vec3i(x, 0, 0), PlannedOccupancy.Cell.SOLID);
        }
        PlannedOccupancy occ = PlannedOccupancy.of(cells);
        assertTrue(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 0, 0), new Vec3i(5, 0, 0), 64));
    }

    @Test
    void singleStepUpAllowed() {
        Map<Vec3i, PlannedOccupancy.Cell> cells = new HashMap<>();
        cells.put(new Vec3i(0, 0, 0), PlannedOccupancy.Cell.SOLID);
        cells.put(new Vec3i(1, 1, 0), PlannedOccupancy.Cell.SOLID);
        PlannedOccupancy occ = PlannedOccupancy.of(cells);
        assertTrue(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 0, 0), new Vec3i(1, 1, 0), 16));
    }

    @Test
    void twoBlockDropWithoutIntermediateFloorFails() {
        Map<Vec3i, PlannedOccupancy.Cell> cells = new HashMap<>();
        cells.put(new Vec3i(0, 2, 0), PlannedOccupancy.Cell.SOLID);
        cells.put(new Vec3i(1, 0, 0), PlannedOccupancy.Cell.SOLID);
        PlannedOccupancy occ = PlannedOccupancy.of(cells);
        assertFalse(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 2, 0), new Vec3i(1, 0, 0), 16),
                "step ±1 only — cannot drop 2 without mid floor");
    }

    @Test
    void lowClearanceBlocksStanding() {
        Map<Vec3i, PlannedOccupancy.Cell> cells = new HashMap<>();
        for (int x = 0; x <= 3; x++) {
            cells.put(new Vec3i(x, 0, 0), PlannedOccupancy.Cell.SOLID);
            cells.put(new Vec3i(x, 2, 0), PlannedOccupancy.Cell.SOLID); // head height blocked
        }
        PlannedOccupancy occ = PlannedOccupancy.of(cells);
        assertFalse(WalkabilityGraph.canStand(occ, 1, 0, 0));
        assertFalse(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 0, 0), new Vec3i(3, 0, 0), 32));
    }

    @Test
    void largeCoordinatesDoNotCollideInSeenSet() {
        Map<Vec3i, PlannedOccupancy.Cell> cells = new HashMap<>();
        // Path near x=0 and a distant solid that would alias under 10-bit pack (+512 & 0x3FF).
        for (int x = 0; x <= 4; x++) {
            cells.put(new Vec3i(x, 10, 0), PlannedOccupancy.Cell.SOLID);
        }
        cells.put(new Vec3i(1024, 10, 0), PlannedOccupancy.Cell.SOLID);
        PlannedOccupancy occ = PlannedOccupancy.of(cells);
        assertTrue(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 10, 0), new Vec3i(4, 10, 0), 32));
        assertFalse(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 10, 0), new Vec3i(1024, 10, 0), 32),
                "distant cell must not alias into the short path");
    }

    @Test
    void portalRemoveClearsBodyButNeedsFloorSupport() {
        List<BlockPatch> patches = new ArrayList<>();
        // Floor corridor
        for (int x = 0; x <= 4; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 0, "minecraft:oak_planks"));
        }
        // Wall then portal carve at body height
        patches.add(new BlockPatch(BlockPatch.PLACE, 2, 1, 0, "minecraft:stone"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 2, 2, 0, "minecraft:stone"));
        patches.add(new BlockPatch(BlockPatch.REMOVE, 2, 1, 0, "minecraft:air"));
        patches.add(new BlockPatch(BlockPatch.REMOVE, 2, 2, 0, "minecraft:air"));
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches);
        assertEquals(PlannedOccupancy.Cell.PLANNED_AIR, occ.get(2, 1, 0));
        assertTrue(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 0, 0), new Vec3i(4, 0, 0), 32));
    }
}
