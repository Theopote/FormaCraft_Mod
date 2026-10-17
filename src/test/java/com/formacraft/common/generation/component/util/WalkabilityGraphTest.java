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

    private static PlannedOccupancy fullAt(Vec3i... positions) {
        Map<Vec3i, PlannedOccupancy.CollisionProfile> cells = new HashMap<>();
        for (Vec3i p : positions) {
            cells.put(p, PlannedOccupancy.CollisionProfile.FULL);
        }
        return PlannedOccupancy.ofProfiles(cells);
    }

    @Test
    void standingRequiresSupportFeetAndHead() {
        Map<Vec3i, PlannedOccupancy.CollisionProfile> cells = new HashMap<>();
        cells.put(new Vec3i(0, 0, 0), PlannedOccupancy.CollisionProfile.FULL);
        PlannedOccupancy occ = PlannedOccupancy.ofProfiles(cells);
        assertTrue(WalkabilityGraph.canStand(occ, 0, 0, 0));

        cells.put(new Vec3i(0, 2, 0), PlannedOccupancy.CollisionProfile.FULL);
        occ = PlannedOccupancy.ofProfiles(cells);
        assertFalse(WalkabilityGraph.canStand(occ, 0, 0, 0), "head blocked");

        cells.remove(new Vec3i(0, 0, 0));
        cells.put(new Vec3i(0, 1, 0), PlannedOccupancy.CollisionProfile.AIR);
        occ = PlannedOccupancy.ofProfiles(cells);
        assertFalse(WalkabilityGraph.canStand(occ, 0, 0, 0), "no solid support");
    }

    @Test
    void removeIsPlannedAirNotUntouched() {
        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 0, 1, "minecraft:stone"),
                new BlockPatch(BlockPatch.REMOVE, 1, 1, 1, "minecraft:air")
        );
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches);
        assertEquals(PlannedOccupancy.CollisionProfile.FULL, occ.profileAt(1, 0, 1));
        assertEquals(PlannedOccupancy.CollisionProfile.AIR, occ.profileAt(1, 1, 1));
        assertEquals(PlannedOccupancy.CollisionProfile.UNTOUCHED, occ.profileAt(9, 9, 9));
        assertTrue(occ.isBodyClear(1, 1, 1));
        assertFalse(occ.isSupport(1, 1, 1));
        assertFalse(occ.isSupport(9, 9, 9));
    }

    @Test
    void floatingAirPathCannotReach() {
        PlannedOccupancy occ = fullAt(new Vec3i(0, 0, 0), new Vec3i(5, 0, 0));
        assertFalse(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 0, 0), new Vec3i(5, 0, 0), 64));
    }

    @Test
    void continuousFloorPathReaches() {
        Map<Vec3i, PlannedOccupancy.CollisionProfile> cells = new HashMap<>();
        for (int x = 0; x <= 5; x++) {
            cells.put(new Vec3i(x, 0, 0), PlannedOccupancy.CollisionProfile.FULL);
        }
        PlannedOccupancy occ = PlannedOccupancy.ofProfiles(cells);
        assertTrue(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 0, 0), new Vec3i(5, 0, 0), 64));
    }

    @Test
    void singleStepUpAllowed() {
        PlannedOccupancy occ = fullAt(new Vec3i(0, 0, 0), new Vec3i(1, 1, 0));
        assertTrue(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 0, 0), new Vec3i(1, 1, 0), 16));
    }

    @Test
    void twoBlockDropWithoutIntermediateFloorFails() {
        PlannedOccupancy occ = fullAt(new Vec3i(0, 2, 0), new Vec3i(1, 0, 0));
        assertFalse(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 2, 0), new Vec3i(1, 0, 0), 16),
                "step ±1 only — cannot drop 2 without mid floor");
    }

    @Test
    void lowClearanceBlocksStanding() {
        Map<Vec3i, PlannedOccupancy.CollisionProfile> cells = new HashMap<>();
        for (int x = 0; x <= 3; x++) {
            cells.put(new Vec3i(x, 0, 0), PlannedOccupancy.CollisionProfile.FULL);
            cells.put(new Vec3i(x, 2, 0), PlannedOccupancy.CollisionProfile.FULL);
        }
        PlannedOccupancy occ = PlannedOccupancy.ofProfiles(cells);
        assertFalse(WalkabilityGraph.canStand(occ, 1, 0, 0));
        assertFalse(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 0, 0), new Vec3i(3, 0, 0), 32));
    }

    @Test
    void largeCoordinatesDoNotCollideInSeenSet() {
        Map<Vec3i, PlannedOccupancy.CollisionProfile> cells = new HashMap<>();
        for (int x = 0; x <= 4; x++) {
            cells.put(new Vec3i(x, 10, 0), PlannedOccupancy.CollisionProfile.FULL);
        }
        cells.put(new Vec3i(1024, 10, 0), PlannedOccupancy.CollisionProfile.FULL);
        PlannedOccupancy occ = PlannedOccupancy.ofProfiles(cells);
        assertTrue(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 10, 0), new Vec3i(4, 10, 0), 32));
        assertFalse(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 10, 0), new Vec3i(1024, 10, 0), 32),
                "distant cell must not alias into the short path");
    }

    @Test
    void portalRemoveClearsBodyButNeedsFloorSupport() {
        List<BlockPatch> patches = new ArrayList<>();
        for (int x = 0; x <= 4; x++) {
            patches.add(new BlockPatch(BlockPatch.PLACE, x, 0, 0, "minecraft:oak_planks"));
        }
        patches.add(new BlockPatch(BlockPatch.PLACE, 2, 1, 0, "minecraft:stone"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 2, 2, 0, "minecraft:stone"));
        patches.add(new BlockPatch(BlockPatch.REMOVE, 2, 1, 0, "minecraft:air"));
        patches.add(new BlockPatch(BlockPatch.REMOVE, 2, 2, 0, "minecraft:air"));
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches);
        assertEquals(PlannedOccupancy.CollisionProfile.AIR, occ.profileAt(2, 1, 0));
        assertTrue(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 0, 0), new Vec3i(4, 0, 0), 32));
    }

    @Test
    void stairsAreNotAirAndSupportStanding() {
        assertFalse(PlannedOccupancy.isAirId("minecraft:oak_stairs"));
        assertEquals(PlannedOccupancy.CollisionProfile.STAIR,
                PlannedOccupancy.classify("minecraft:oak_stairs", false));
        assertEquals(PlannedOccupancy.CollisionProfile.STAIR,
                PlannedOccupancy.classify("minecraft:quartz_stairs[facing=east,half=bottom]", false));
        assertEquals(PlannedOccupancy.CollisionProfile.STAIR,
                PlannedOccupancy.classify("minecraft:oak_stairs[facing=north,half=top]", false));

        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:oak_stairs[facing=east,half=bottom]"),
                new BlockPatch(BlockPatch.PLACE, 1, 0, 0, "minecraft:oak_stairs"),
                new BlockPatch(BlockPatch.PLACE, 2, 0, 0, "minecraft:oak_planks")
        );
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches);
        assertEquals(PlannedOccupancy.CollisionProfile.STAIR, occ.profileAt(0, 0, 0));
        assertEquals(PlannedOccupancy.CollisionProfile.STAIR, occ.profileAt(1, 0, 0));
        assertTrue(WalkabilityGraph.canStand(occ, 0, 0, 0));
        assertTrue(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 0, 0), new Vec3i(2, 0, 0), 16));
    }

    @Test
    void slabFenceCarpetProfilesDifferFromFull() {
        assertEquals(PlannedOccupancy.CollisionProfile.SLAB_BOTTOM,
                PlannedOccupancy.classify("minecraft:oak_slab[half=bottom]", false));
        assertEquals(PlannedOccupancy.CollisionProfile.SLAB_TOP,
                PlannedOccupancy.classify("minecraft:oak_slab[half=top]", false));
        assertEquals(PlannedOccupancy.CollisionProfile.FENCE,
                PlannedOccupancy.classify("minecraft:oak_fence", false));
        assertEquals(PlannedOccupancy.CollisionProfile.CARPET,
                PlannedOccupancy.classify("minecraft:white_carpet", false));

        PlannedOccupancy slab = PlannedOccupancy.fromPatches(List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:oak_slab[half=bottom]"),
                new BlockPatch(BlockPatch.PLACE, 1, 0, 0, "minecraft:oak_planks")
        ));
        assertTrue(WalkabilityGraph.canStand(slab, 0, 0, 0));
        assertTrue(WalkabilityGraph.canReach(slab, new Vec3i(0, 0, 0), new Vec3i(1, 0, 0), 8));

        PlannedOccupancy fence = PlannedOccupancy.fromPatches(List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:oak_fence")
        ));
        assertFalse(fence.isSupport(0, 0, 0));
        assertTrue(fence.profileAt(0, 0, 0).blocksBody());
        assertFalse(WalkabilityGraph.canStand(fence, 0, 0, 0));

        PlannedOccupancy carpet = PlannedOccupancy.fromPatches(List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:white_carpet")
        ));
        assertTrue(carpet.isSupport(0, 0, 0));
        assertTrue(carpet.isBodyClear(0, 0, 0), "carpet must not block body in its own cell");
        assertTrue(WalkabilityGraph.canStand(carpet, 0, 0, 0));
    }

    @Test
    void stepUpBlockedWhenOriginLiftClearanceMissing() {
        Map<Vec3i, PlannedOccupancy.CollisionProfile> cells = new HashMap<>();
        cells.put(new Vec3i(0, 0, 0), PlannedOccupancy.CollisionProfile.FULL);
        cells.put(new Vec3i(1, 1, 0), PlannedOccupancy.CollisionProfile.FULL);
        cells.put(new Vec3i(0, 3, 0), PlannedOccupancy.CollisionProfile.FULL);
        PlannedOccupancy occ = PlannedOccupancy.ofProfiles(cells);
        assertTrue(WalkabilityGraph.canStand(occ, 0, 0, 0));
        assertTrue(WalkabilityGraph.canStand(occ, 1, 1, 0));
        assertFalse(WalkabilityGraph.canTraverse(
                occ, new Vec3i(0, 0, 0), new Vec3i(1, 1, 0)));
        assertFalse(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 0, 0), new Vec3i(1, 1, 0), 16));
    }

    @Test
    void bridgeDeckViaStairsToInteriorFloor() {
        List<BlockPatch> patches = new ArrayList<>();
        patches.add(new BlockPatch(BlockPatch.PLACE, 0, 4, 0, "minecraft:spruce_planks"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 4, 0, "minecraft:oak_stairs[facing=east,half=bottom]"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 2, 4, 0, "minecraft:oak_planks"));
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches);
        assertTrue(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 4, 0), new Vec3i(2, 4, 0), 16));
    }

    @Test
    void searchLimitDistinctFromUnreachable() {
        Map<Vec3i, PlannedOccupancy.CollisionProfile> cells = new HashMap<>();
        for (int x = 0; x <= 20; x++) {
            cells.put(new Vec3i(x, 0, 0), PlannedOccupancy.CollisionProfile.FULL);
        }
        PlannedOccupancy occ = PlannedOccupancy.ofProfiles(cells);
        assertEquals(WalkabilityGraph.ReachResult.REACHABLE,
                WalkabilityGraph.search(occ, new Vec3i(0, 0, 0), new Vec3i(20, 0, 0), 64, null));
        assertEquals(WalkabilityGraph.ReachResult.SEARCH_LIMIT,
                WalkabilityGraph.search(occ, new Vec3i(0, 0, 0), new Vec3i(20, 0, 0), 5, null),
                "depth cap with unexplored neighbors must be SEARCH_LIMIT");
        PlannedOccupancy islands = fullAt(new Vec3i(0, 0, 0), new Vec3i(20, 0, 0));
        assertEquals(WalkabilityGraph.ReachResult.UNREACHABLE,
                WalkabilityGraph.search(islands, new Vec3i(0, 0, 0), new Vec3i(20, 0, 0), 512, null));
    }

    @Test
    void depthCapExhaustedWithoutDeferredIsUnreachable() {
        // Reachable chain length 2 (dist 0..2); goal isolated. At maxDepth=2 every frontier
        // node has no further unseen neighbor → UNREACHABLE, not SEARCH_LIMIT.
        Map<Vec3i, PlannedOccupancy.CollisionProfile> cells = new HashMap<>();
        cells.put(new Vec3i(0, 0, 0), PlannedOccupancy.CollisionProfile.FULL);
        cells.put(new Vec3i(1, 0, 0), PlannedOccupancy.CollisionProfile.FULL);
        cells.put(new Vec3i(2, 0, 0), PlannedOccupancy.CollisionProfile.FULL);
        cells.put(new Vec3i(9, 0, 0), PlannedOccupancy.CollisionProfile.FULL);
        PlannedOccupancy occ = PlannedOccupancy.ofProfiles(cells);
        assertEquals(WalkabilityGraph.ReachResult.UNREACHABLE,
                WalkabilityGraph.search(occ, new Vec3i(0, 0, 0), new Vec3i(9, 0, 0), 2, null),
                "finished exploring all nodes at depth cap must be UNREACHABLE");
    }

    @Test
    void visitCapWithPendingNeighborsIsSearchLimit() {
        Map<Vec3i, PlannedOccupancy.CollisionProfile> cells = new HashMap<>();
        for (int x = 0; x <= 10; x++) {
            cells.put(new Vec3i(x, 0, 0), PlannedOccupancy.CollisionProfile.FULL);
        }
        PlannedOccupancy occ = PlannedOccupancy.ofProfiles(cells);
        var budget = new WalkabilityGraph.SearchBudget(64, 3);
        assertEquals(WalkabilityGraph.ReachResult.SEARCH_LIMIT,
                WalkabilityGraph.search(occ, new Vec3i(0, 0, 0), new Vec3i(10, 0, 0), budget, null));
    }

    @Test
    void visitBudgetExactPathLengthIsReachable() {
        // Path visits: start + 1 + 2 + 3 + 4 = 5 dequeues to reach goal at x=4.
        Map<Vec3i, PlannedOccupancy.CollisionProfile> cells = new HashMap<>();
        for (int x = 0; x <= 4; x++) {
            cells.put(new Vec3i(x, 0, 0), PlannedOccupancy.CollisionProfile.FULL);
        }
        PlannedOccupancy occ = PlannedOccupancy.ofProfiles(cells);
        var exact = new WalkabilityGraph.SearchBudget(64, 5);
        assertEquals(WalkabilityGraph.ReachResult.REACHABLE,
                WalkabilityGraph.search(occ, new Vec3i(0, 0, 0), new Vec3i(4, 0, 0), exact, null));
    }

    @Test
    void visitBudgetExhaustedWithQueueRemainingIsSearchLimit() {
        Map<Vec3i, PlannedOccupancy.CollisionProfile> cells = new HashMap<>();
        for (int x = 0; x <= 8; x++) {
            cells.put(new Vec3i(x, 0, 0), PlannedOccupancy.CollisionProfile.FULL);
        }
        PlannedOccupancy occ = PlannedOccupancy.ofProfiles(cells);
        var tight = new WalkabilityGraph.SearchBudget(64, 2);
        assertEquals(WalkabilityGraph.ReachResult.SEARCH_LIMIT,
                WalkabilityGraph.search(occ, new Vec3i(0, 0, 0), new Vec3i(8, 0, 0), tight, null));
    }

    @Test
    void goalQueuedButNotDequeuedUnderVisitCapIsSearchLimit() {
        // Linear floor: with maxVisits=3 we dequeue x=0,1,2 then stop. Goal x=4 may already
        // be enqueued from x=2's expand under soft semantics, but hard-stop must not REACHABLE.
        Map<Vec3i, PlannedOccupancy.CollisionProfile> cells = new HashMap<>();
        for (int x = 0; x <= 4; x++) {
            cells.put(new Vec3i(x, 0, 0), PlannedOccupancy.CollisionProfile.FULL);
        }
        PlannedOccupancy occ = PlannedOccupancy.ofProfiles(cells);
        // visits=4 would dequeue goal; cap at 3 leaves goal only queued (or never visited).
        var beforeGoal = new WalkabilityGraph.SearchBudget(64, 3);
        var outcome = WalkabilityGraph.searchDetailed(
                occ, new Vec3i(0, 0, 0), new Vec3i(4, 0, 0), beforeGoal, null);
        assertEquals(WalkabilityGraph.ReachResult.SEARCH_LIMIT, outcome.result(),
                "goal must be dequeued within maxVisits to count as REACHABLE");
        assertNotEquals(WalkabilityGraph.ReachResult.REACHABLE, outcome.result());
        assertEquals(PlannedOccupancy.WalkabilityPrecision.FAST, outcome.precision());
    }

    @Test
    void visitBudgetExhaustedReachableSetIsUnreachable() {
        PlannedOccupancy islands = fullAt(new Vec3i(0, 0, 0), new Vec3i(5, 0, 0));
        assertEquals(WalkabilityGraph.ReachResult.UNREACHABLE,
                WalkabilityGraph.search(islands, new Vec3i(0, 0, 0), new Vec3i(5, 0, 0),
                        new WalkabilityGraph.SearchBudget(64, 64), null));
    }

    @Test
    void adaptiveBudgetClampsHugeManhattanAndVolume() {
        Vec3i start = new Vec3i(0, 0, 0);
        Vec3i goal = new Vec3i(1_000_000, 500_000, 1_000_000);
        var bounds = new ComponentFootprintUtil.Bounds(
                0, 0, 0, 100_000, 100_000, 100_000);
        var budget = WalkabilityGraph.adaptiveBudget(start, goal, bounds);
        assertTrue(budget.maxDepth() > 0);
        assertTrue(budget.maxVisits() > 0);
        assertEquals(WalkabilityGraph.GLOBAL_MAX_DEPTH, budget.maxDepth());
        // 4 * GLOBAL_MAX_DEPTH = 32768 < GLOBAL_MAX_VISITS; must not int-wrap to a tiny value
        assertEquals(4 * WalkabilityGraph.GLOBAL_MAX_DEPTH, budget.maxVisits());
        assertTrue(budget.maxVisits() <= WalkabilityGraph.GLOBAL_MAX_VISITS);
    }

    @Test
    void adaptiveBudgetKeepsSmallSpanFloors() {
        var budget = WalkabilityGraph.adaptiveBudget(
                new Vec3i(0, 4, 0), new Vec3i(12, 4, 0), null);
        assertTrue(budget.maxDepth() >= 512);
        assertTrue(budget.maxVisits() >= 4096);
        assertTrue(budget.maxDepth() <= WalkabilityGraph.GLOBAL_MAX_DEPTH);
        assertTrue(budget.maxVisits() <= WalkabilityGraph.GLOBAL_MAX_VISITS);
    }

    @Test
    void ofDepthClampsToGlobalMax() {
        var budget = WalkabilityGraph.SearchBudget.ofDepth(Integer.MAX_VALUE);
        assertEquals(WalkabilityGraph.GLOBAL_MAX_DEPTH, budget.maxDepth());
        assertEquals(4 * WalkabilityGraph.GLOBAL_MAX_DEPTH, budget.maxVisits());
    }

    @Test
    void searchBudgetConstructorClampsExplicitVisits() {
        var budget = new WalkabilityGraph.SearchBudget(
                WalkabilityGraph.GLOBAL_MAX_DEPTH + 999,
                WalkabilityGraph.GLOBAL_MAX_VISITS + 999);
        assertEquals(WalkabilityGraph.GLOBAL_MAX_DEPTH, budget.maxDepth());
        assertEquals(WalkabilityGraph.GLOBAL_MAX_VISITS, budget.maxVisits());
    }

    @Test
    void preferRegistryFalseIsFastPrecision() {
        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:oak_planks")
        );
        assertEquals(PlannedOccupancy.WalkabilityPrecision.FAST,
                PlannedOccupancy.fromPatches(patches, false).precision());
    }

    @Test
    void preferRegistryWithUnparseableIdIsMixed() {
        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:oak_planks"),
                new BlockPatch(BlockPatch.PLACE, 1, 0, 0, "formacraft:not_a_real_block_zzz")
        );
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches, true);
        assertEquals(PlannedOccupancy.WalkabilityPrecision.MIXED, occ.precision(),
                "registry fallback must not claim BLOCKSTATE");
    }

    @Test
    void preferRegistryAllParsedIsBlockStateWhenRegistryLive() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:oak_planks"),
                new BlockPatch(BlockPatch.PLACE, 1, 0, 0, "minecraft:oak_planks")
        );
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches, true);
        assertEquals(PlannedOccupancy.WalkabilityPrecision.BLOCKSTATE, occ.precision());
        var outcome = WalkabilityGraph.searchDetailed(
                occ, new Vec3i(0, 0, 0), new Vec3i(1, 0, 0),
                new WalkabilityGraph.SearchBudget(8, 16), null);
        assertEquals(WalkabilityGraph.ReachResult.REACHABLE, outcome.result());
        assertEquals(PlannedOccupancy.WalkabilityPrecision.BLOCKSTATE, outcome.precision());
    }

    @Test
    void slabBottomConnectsToAdjacentFullFloor() {
        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:oak_slab[half=bottom]"),
                new BlockPatch(BlockPatch.PLACE, 1, 0, 0, "minecraft:oak_planks")
        );
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches);
        assertEquals(4, occ.profileAt(0, 0, 0).supportTopEighths());
        assertEquals(8, occ.profileAt(1, 0, 0).supportTopEighths());
        assertTrue(WalkabilityGraph.canTraverse(
                occ,
                WalkabilityGraph.poseAt(occ, 0, 0, 0),
                WalkabilityGraph.poseAt(occ, 1, 0, 0)));
        assertTrue(WalkabilityGraph.canReach(
                occ, new Vec3i(0, 0, 0), new Vec3i(1, 0, 0), 8));
    }
}
