package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StoreyWalkRegionsTest {

    private static Component massAt(int x, int y, int z, int w, int d, int h) {
        return new Component(
                "MASS_MAIN", "m", new Vec3i(x, y, z), new Dimensions(w, d, h), List.of(),
                Map.of("component_id", "m", "wall_thickness", 1, "floor_count", 1, "floor_height", 4,
                        "anchor_mode", "min_corner"));
    }

    @Test
    void scanAppliesSlotOffsetBeforeOccupancyLookup() {
        Component mass = massAt(0, 0, 0, 8, 8, 8);
        Vec3i slot = new Vec3i(5, 2, 3);
        // Floor only at world (6, 2, 7) = local interior (1,0,4) + slot
        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 6, 2, 7, "minecraft:oak_planks"),
                new BlockPatch(BlockPatch.PLACE, 7, 2, 7, "minecraft:oak_planks"));
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches);
        var scan = StoreyWalkRegions.scan(occ, mass, 0, slot);
        assertEquals(StoreyWalkRegions.Status.OK, scan.status());
        assertFalse(scan.representatives().isEmpty());
        for (Vec3i rep : scan.representatives()) {
            assertEquals(2, rep.y());
            assertTrue(rep.x() >= 5);
            assertTrue(rep.z() >= 3);
        }
    }

    @Test
    void scanMissesWhenFloorOnlyInUnshiftedCoords() {
        Component mass = massAt(0, 0, 0, 8, 8, 8);
        Vec3i slot = new Vec3i(5, 2, 3);
        // Floor at unshifted local interior — wrong for slot
        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"),
                new BlockPatch(BlockPatch.PLACE, 2, 0, 4, "minecraft:oak_planks"));
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches);
        var scan = StoreyWalkRegions.scan(occ, mass, 0, slot);
        assertEquals(StoreyWalkRegions.Status.EMPTY, scan.status());
    }

    @Test
    void oversizedFootprintReturnsScanLimitNotSinglePoint() {
        Component mass = massAt(0, 0, 0, 80, 80, 8);
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 0, 40, "minecraft:oak_planks")));
        var scan = StoreyWalkRegions.scan(occ, mass, 0, new Vec3i(0, 0, 0));
        assertEquals(StoreyWalkRegions.Status.SCAN_LIMIT, scan.status());
        assertTrue(scan.areaCells() > StoreyWalkRegions.MAX_CELLS);
        assertTrue(scan.representatives().isEmpty());
    }

    @Test
    void disconnectedStandablePocketsYieldSeparateRepresentatives() {
        Component mass = massAt(0, 0, 0, 12, 8, 8);
        List<BlockPatch> patches = new ArrayList<>();
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 2, 0, 4, "minecraft:oak_planks"));
        // gap at x=3..7
        patches.add(new BlockPatch(BlockPatch.PLACE, 8, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 9, 0, 4, "minecraft:oak_planks"));
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches);
        var scan = StoreyWalkRegions.scan(occ, mass, 0, null);
        assertEquals(StoreyWalkRegions.Status.OK, scan.status());
        assertEquals(2, scan.representatives().size());
    }

    @Test
    void connectedStandableStripYieldsSingleRepresentative() {
        Component mass = massAt(0, 0, 0, 12, 8, 8);
        List<BlockPatch> patches = new ArrayList<>();
        for (int x = 1; x <= 9; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 4, "minecraft:oak_planks"));
        }
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches);
        var scan = StoreyWalkRegions.scan(occ, mass, 0, null);
        assertEquals(StoreyWalkRegions.Status.OK, scan.status());
        assertEquals(1, scan.representatives().size());
    }

    @Test
    void adjacentStandablesWithBlockedTraverseYieldSeparateRepresentatives() {
        Component mass = massAt(0, 0, 0, 12, 8, 8);
        List<BlockPatch> patches = new ArrayList<>();
        // 4-adjacent standable cells — would merge if flood only required mutual canStand
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 0, 4, "minecraft:oak_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 2, 0, 4, "minecraft:oak_planks"));
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches);
        assertTrue(WalkabilityGraph.canStand(occ, 1, 0, 4));
        assertTrue(WalkabilityGraph.canStand(occ, 2, 0, 4));
        // Same-Y full floors are traversable in production; override simulates support-height
        // / clearance rejection on the shared edge.
        StoreyWalkRegions.traverseEdgeOverride = (a, b) -> false;
        try {
            var scan = StoreyWalkRegions.scan(occ, mass, 0, null);
            assertEquals(StoreyWalkRegions.Status.OK, scan.status());
            assertEquals(2, scan.representatives().size());
        } finally {
            StoreyWalkRegions.traverseEdgeOverride = null;
        }
    }
}
