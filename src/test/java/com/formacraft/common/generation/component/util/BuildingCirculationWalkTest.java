package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Building-scale circulation: floor → stair → corridor → bridge deck → far interior.
 * Synthetic BlockPatches only (no full LlmPlan compile).
 */
class BuildingCirculationWalkTest {

    private static void placeFloor(List<BlockPatch> patches, int x0, int x1, int y, int z, String id) {
        for (int x = x0; x <= x1; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, y, z, id));
        }
    }

    /** Indoor Y=0 → stair → Y=1 corridor → bridge deck → far indoor. */
    private static List<BlockPatch> circulationPatches() {
        List<BlockPatch> patches = new ArrayList<>();
        // Ground floor interior x=0..2 at y=0, z=0
        placeFloor(patches, 0, 2, 0, 0, "minecraft:oak_planks");
        // Stair step onto second storey (support at y=0, surface mid → adjacent y=1 floor)
        patches.add(new BlockPatch(BlockPatch.PLACE, 3, 0, 0, "minecraft:oak_stairs[facing=east,half=bottom]"));
        // Second floor / corridor x=4..6 at y=1, z=0
        placeFloor(patches, 4, 6, 1, 0, "minecraft:oak_planks");
        // Bridge deck continues at y=1 toward far mass
        placeFloor(patches, 7, 9, 1, 0, "minecraft:spruce_planks");
        // Far interior at y=1
        placeFloor(patches, 10, 12, 1, 0, "minecraft:oak_planks");
        return patches;
    }

    @Test
    void fullCirculationFloorStairCorridorBridgeIsReachable() {
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(circulationPatches());
        assertTrue(WalkabilityGraph.canStand(occ, 0, 0, 0));
        assertTrue(WalkabilityGraph.canStand(occ, 3, 0, 0), "stair support");
        assertTrue(WalkabilityGraph.canStand(occ, 4, 1, 0), "upper corridor");
        assertTrue(WalkabilityGraph.canStand(occ, 8, 1, 0), "bridge deck");
        assertTrue(WalkabilityGraph.canStand(occ, 12, 1, 0), "far interior");

        assertEquals(WalkabilityGraph.ReachResult.REACHABLE,
                WalkabilityGraph.search(
                        occ, new Vec3i(0, 0, 0), new Vec3i(12, 1, 0), 64, null));
        assertTrue(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 0, 0), new Vec3i(12, 1, 0), 64));
    }

    @Test
    void missingUpperFloorBreaksCirculation() {
        List<BlockPatch> patches = circulationPatches();
        // Remove second-storey corridor cells (x=4..6)
        patches.removeIf(p -> p.dy() == 1 && p.dx() >= 4 && p.dx() <= 6);
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches);
        assertEquals(WalkabilityGraph.ReachResult.UNREACHABLE,
                WalkabilityGraph.search(
                        occ, new Vec3i(0, 0, 0), new Vec3i(12, 1, 0), 64, null));
    }

    @Test
    void blockedHeadClearanceAtDoorBreaksStandingPath() {
        List<BlockPatch> patches = circulationPatches();
        // Plug body clearance above ground corridor (feet y=1, head y=2) at x=2
        patches.add(new BlockPatch(BlockPatch.PLACE, 2, 2, 0, "minecraft:stone"));
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches);
        assertFalse(WalkabilityGraph.canStand(occ, 2, 0, 0), "head blocked at doorway");
        assertEquals(WalkabilityGraph.ReachResult.UNREACHABLE,
                WalkabilityGraph.search(
                        occ, new Vec3i(0, 0, 0), new Vec3i(12, 1, 0), 64, null));
    }
}
