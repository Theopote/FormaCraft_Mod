package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;

import java.util.Map;

/**
 * Bridge-specific walkability over final BlockPatches via {@link WalkabilityGraph}:
 * standing nodes (support + feet + head), not air 6-connectivity.
 */
public final class BridgeWalkability {
    public enum BridgeReach {
        CONNECTED,
        PATH_BLOCKED,
        SEARCH_LIMIT
    }

    /** Bridge reach plus occupancy precision ({@code BLOCKSTATE} ≠ VoxelShape). */
    public record BridgeWalkOutcome(BridgeReach reach, PlannedOccupancy.WalkabilityPrecision precision) {
        public BridgeWalkOutcome {
            if (reach == null) {
                reach = BridgeReach.PATH_BLOCKED;
            }
            if (precision == null) {
                precision = PlannedOccupancy.WalkabilityPrecision.FAST;
            }
        }
    }

    private BridgeWalkability() {}

    /** @deprecated Prefer {@link PlannedOccupancy#isBodyClear}. */
    @Deprecated
    public static boolean isPassable(Map<Vec3i, String> blocks, int x, int y, int z) {
        String id = blocks == null ? null : blocks.get(new Vec3i(x, y, z));
        return id == null || isAir(id);
    }

    public static boolean isAir(String blockId) {
        return PlannedOccupancy.isAirId(blockId);
    }

    public static boolean canReach(Map<Vec3i, String> blocks, Vec3i startFeet, Vec3i goalFeet, int maxSteps) {
        if (startFeet == null || goalFeet == null) {
            return false;
        }
        PlannedOccupancy occ = fromLegacyBlocks(blocks);
        Vec3i startFloor = new Vec3i(startFeet.x(), startFeet.y() - 1, startFeet.z());
        Vec3i goalFloor = new Vec3i(goalFeet.x(), goalFeet.y() - 1, goalFeet.z());
        return WalkabilityGraph.canReach(occ, startFloor, goalFloor, maxSteps);
    }

    public static boolean canReach(PlannedOccupancy occ, Vec3i startFloor, Vec3i goalFloor, int maxSteps) {
        return WalkabilityGraph.canReach(occ, startFloor, goalFloor, maxSteps);
    }

    public static Vec3i interiorFloorCell(Component mass, int plateY) {
        var bounds = ComponentFootprintUtil.bounds(mass);
        if (bounds == null) {
            return null;
        }
        int thickness = Math.max(1, ComponentParamParsers.intParam(
                mass.params(), 1, "wall_thickness", "wallThickness"));
        int x = bounds.minX() + thickness;
        int z = bounds.minZ() + Math.max(thickness, bounds.depth() / 2);
        if (x >= bounds.maxX() - thickness) {
            x = (bounds.minX() + bounds.maxX()) / 2;
        }
        if (z >= bounds.maxZ() - thickness) {
            z = (bounds.minZ() + bounds.maxZ()) / 2;
        }
        return new Vec3i(x, plateY, z);
    }

    /** @deprecated Use {@link #interiorFloorCell}. */
    @Deprecated
    public static Vec3i interiorStandingCell(Component mass, int plateY) {
        Vec3i floor = interiorFloorCell(mass, plateY);
        return floor == null ? null : new Vec3i(floor.x(), floor.y() + 1, floor.z());
    }

    public static Vec3i deckFloorCell(Component plate) {
        var bounds = ComponentFootprintUtil.bounds(plate);
        if (bounds == null || plate.relativePosition() == null) {
            return null;
        }
        int x = (bounds.minX() + bounds.maxX()) / 2;
        int z = (bounds.minZ() + bounds.maxZ()) / 2;
        return new Vec3i(x, plate.relativePosition().y(), z);
    }

    /** @deprecated Use {@link #deckFloorCell}. */
    @Deprecated
    public static Vec3i deckStandingCell(Component plate) {
        Vec3i floor = deckFloorCell(plate);
        return floor == null ? null : new Vec3i(floor.x(), floor.y() + 1, floor.z());
    }

    public static boolean bridgeConnectsInteriors(
            Map<Vec3i, String> blocks,
            Component plate,
            Component host,
            Component target
    ) {
        return evaluateBridge(PlannedOccupancy.fromPatches(legacyToPatches(blocks)),
                plate, ZERO, host, ZERO, target, ZERO) == BridgeReach.CONNECTED;
    }

    public static boolean bridgeConnectsInteriors(
            PlannedOccupancy occ,
            Component plate,
            Component host,
            Component target
    ) {
        return evaluateBridge(occ, plate, ZERO, host, ZERO, target, ZERO) == BridgeReach.CONNECTED;
    }

    public static boolean bridgeConnectsInteriors(
            Map<Vec3i, String> blocks,
            Component plate,
            Vec3i plateOffset,
            Component host,
            Vec3i hostOffset,
            Component target,
            Vec3i targetOffset
    ) {
        return evaluateBridge(
                PlannedOccupancy.fromPatches(legacyToPatches(blocks)),
                plate, plateOffset, host, hostOffset, target, targetOffset) == BridgeReach.CONNECTED;
    }

    public static boolean bridgeConnectsInteriors(
            PlannedOccupancy occ,
            Component plate,
            Vec3i plateOffset,
            Component host,
            Vec3i hostOffset,
            Component target,
            Vec3i targetOffset
    ) {
        return evaluateBridge(occ, plate, plateOffset, host, hostOffset, target, targetOffset)
                == BridgeReach.CONNECTED;
    }

    /**
     * Deck and both mass interiors must be legal standing floors at {@code plateY},
     * then BFS with step ±1 within the union search bounds.
     */
    public static BridgeReach evaluateBridge(
            PlannedOccupancy occ,
            Component plate,
            Vec3i plateOffset,
            Component host,
            Vec3i hostOffset,
            Component target,
            Vec3i targetOffset
    ) {
        return evaluateBridgeDetailed(occ, plate, plateOffset, host, hostOffset, target, targetOffset).reach();
    }

    public static BridgeWalkOutcome evaluateBridgeDetailed(
            PlannedOccupancy occ,
            Component plate,
            Vec3i plateOffset,
            Component host,
            Vec3i hostOffset,
            Component target,
            Vec3i targetOffset
    ) {
        PlannedOccupancy.WalkabilityPrecision precision = occ == null
                ? PlannedOccupancy.WalkabilityPrecision.FAST
                : occ.precision();
        if (occ == null || plate == null || plate.relativePosition() == null) {
            return new BridgeWalkOutcome(BridgeReach.PATH_BLOCKED, precision);
        }
        int plateY = plate.relativePosition().y();
        Vec3i deck = shift(deckFloorCell(plate), plateOffset);
        Vec3i hostIn = shift(interiorFloorCell(host, plateY), hostOffset);
        Vec3i targetIn = shift(interiorFloorCell(target, plateY), targetOffset);
        if (deck == null || hostIn == null || targetIn == null) {
            return new BridgeWalkOutcome(BridgeReach.PATH_BLOCKED, precision);
        }
        if (!WalkabilityGraph.canStand(occ, deck)
                || !WalkabilityGraph.canStand(occ, hostIn)
                || !WalkabilityGraph.canStand(occ, targetIn)) {
            return new BridgeWalkOutcome(BridgeReach.PATH_BLOCKED, precision);
        }
        var bounds = WalkabilityGraph.searchBounds(
                ComponentFootprintUtil.bounds(plate), plateOffset,
                ComponentFootprintUtil.bounds(host), hostOffset,
                ComponentFootprintUtil.bounds(target), targetOffset,
                1);
        var budgetHost = WalkabilityGraph.adaptiveBudget(deck, hostIn, bounds);
        var budgetTarget = WalkabilityGraph.adaptiveBudget(deck, targetIn, bounds);
        var hostOut = WalkabilityGraph.searchDetailed(occ, deck, hostIn, budgetHost, bounds);
        var targetOut = WalkabilityGraph.searchDetailed(occ, deck, targetIn, budgetTarget, bounds);
        if (hostOut.result() == WalkabilityGraph.ReachResult.SEARCH_LIMIT
                || targetOut.result() == WalkabilityGraph.ReachResult.SEARCH_LIMIT) {
            return new BridgeWalkOutcome(BridgeReach.SEARCH_LIMIT, precision);
        }
        if (hostOut.result() == WalkabilityGraph.ReachResult.REACHABLE
                && targetOut.result() == WalkabilityGraph.ReachResult.REACHABLE) {
            return new BridgeWalkOutcome(BridgeReach.CONNECTED, precision);
        }
        return new BridgeWalkOutcome(BridgeReach.PATH_BLOCKED, precision);
    }

    /** Compile-path occupancy with registry refinement when available. */
    public static PlannedOccupancy occupancyFromPatches(Iterable<BlockPatch> patches) {
        return PlannedOccupancy.fromPatches(patches, true);
    }

    public static PlannedOccupancy occupancyFromPatches(Iterable<BlockPatch> patches, boolean preferRegistry) {
        return PlannedOccupancy.fromPatches(patches, preferRegistry);
    }

    public static Map<Vec3i, String> blocksFromPatches(Iterable<BlockPatch> patches) {
        Map<Vec3i, String> blocks = new java.util.HashMap<>();
        if (patches == null) {
            return blocks;
        }
        for (BlockPatch patch : patches) {
            if (patch == null) {
                continue;
            }
            Vec3i pos = new Vec3i(patch.dx(), patch.dy(), patch.dz());
            if (BlockPatch.REMOVE.equals(patch.action()) || PlannedOccupancy.isAirId(patch.targetBlock())) {
                blocks.remove(pos);
            } else {
                blocks.put(pos, patch.targetBlock());
            }
        }
        return blocks;
    }

    private static final Vec3i ZERO = new Vec3i(0, 0, 0);

    private static Vec3i shift(Vec3i local, Vec3i offset) {
        if (local == null) {
            return null;
        }
        if (offset == null) {
            return local;
        }
        return new Vec3i(local.x() + offset.x(), local.y() + offset.y(), local.z() + offset.z());
    }

    private static PlannedOccupancy fromLegacyBlocks(Map<Vec3i, String> blocks) {
        return PlannedOccupancy.fromPatches(legacyToPatches(blocks), false);
    }

    private static Iterable<BlockPatch> legacyToPatches(Map<Vec3i, String> blocks) {
        java.util.List<BlockPatch> out = new java.util.ArrayList<>();
        if (blocks == null) {
            return out;
        }
        for (var e : blocks.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || isAir(e.getValue())) {
                continue;
            }
            Vec3i p = e.getKey();
            out.add(new BlockPatch(BlockPatch.PLACE, p.x(), p.y(), p.z(), e.getValue()));
        }
        return out;
    }
}
