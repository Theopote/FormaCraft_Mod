package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Vec3i;
import java.util.HashSet;
import java.util.ArrayDeque;
import com.formacraft.common.patch.BlockPatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bridge-specific walkability over final BlockPatches via {@link WalkabilityGraph}:
 * standing nodes (support + feet + head), not air 6-connectivity.
 * Deck must reach at least one {@link StoreyWalkRegions} representative on each mass at plate Y
 * (not a fixed AABB interior sample).
 */
public final class BridgeWalkability {
    public enum BridgeReach {
        CONNECTED,
        PATH_BLOCKED,
        SEARCH_LIMIT,
        /** Storey region scan hit {@link StoreyWalkRegions#MAX_CELLS} — not a path proof. */
        REGION_SCAN_LIMIT
    }

    /**
     * Bridge reach plus occupancy precision ({@code BLOCKSTATE} ≠ VoxelShape).
     * When {@code reach == REGION_SCAN_LIMIT}, {@code limitedMassId} / {@code storeyY} / {@code areaCells}
     * identify which mass storey exceeded the scan budget.
     */
    public record BridgeWalkOutcome(
            BridgeReach reach,
            PlannedOccupancy.WalkabilityPrecision precision,
            String limitedMassId,
            int storeyY,
            long areaCells
    ) {
        public BridgeWalkOutcome {
            if (reach == null) {
                reach = BridgeReach.PATH_BLOCKED;
            }
            if (precision == null) {
                precision = PlannedOccupancy.WalkabilityPrecision.FAST;
            }
        }

        public BridgeWalkOutcome(BridgeReach reach, PlannedOccupancy.WalkabilityPrecision precision) {
            this(reach, precision, null, 0, 0L);
        }

        public static BridgeWalkOutcome regionScanLimit(
                PlannedOccupancy.WalkabilityPrecision precision,
                String massId,
                int storeyY,
                long areaCells
        ) {
            return new BridgeWalkOutcome(
                    BridgeReach.REGION_SCAN_LIMIT, precision, massId, storeyY, areaCells);
        }
    }

    /** Goals at one storey for bridge endpoints; preserves {@link StoreyWalkRegions.Status}. */
    public record StoreyGoals(
            StoreyWalkRegions.Status status,
            List<Vec3i> goals,
            long areaCells
    ) {
        public StoreyGoals {
            status = status == null ? StoreyWalkRegions.Status.EMPTY : status;
            goals = goals == null ? List.of() : List.copyOf(goals);
        }

        public boolean scanLimited() {
            return status == StoreyWalkRegions.Status.SCAN_LIMIT;
        }

        public boolean isEmpty() {
            return goals.isEmpty();
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
     * Deck must be standable at plate Y; BFS must reach at least one standable storey region
     * on the host and on the target (prefer standable {@link #interiorFloorCell} when present).
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
        if (occ == null || plate == null || plate.relativePosition() == null
                || host == null || target == null) {
            return new BridgeWalkOutcome(BridgeReach.PATH_BLOCKED, precision);
        }
        int plateY = plate.relativePosition().y();
        Vec3i deck = shift(deckFloorCell(plate), plateOffset);
        if (deck == null || !WalkabilityGraph.canStand(occ, deck)) {
            return new BridgeWalkOutcome(BridgeReach.PATH_BLOCKED, precision);
        }
        int worldY = plateY + (plateOffset == null ? 0 : plateOffset.y());
        int hostY = worldY - (hostOffset == null ? 0 : hostOffset.y());
        int targetY = worldY - (targetOffset == null ? 0 : targetOffset.y());
        StoreyGoals hostGoals = storeyGoalsPreferringInterior(occ, host, hostY, hostOffset);
        if (hostGoals.scanLimited()) {
            return BridgeWalkOutcome.regionScanLimit(
                    precision, componentId(host), hostY, hostGoals.areaCells());
        }
        StoreyGoals targetGoals = storeyGoalsPreferringInterior(occ, target, targetY, targetOffset);
        if (targetGoals.scanLimited()) {
            return BridgeWalkOutcome.regionScanLimit(
                    precision, componentId(target), targetY, targetGoals.areaCells());
        }
        if (hostGoals.isEmpty() || targetGoals.isEmpty()) {
            return new BridgeWalkOutcome(BridgeReach.PATH_BLOCKED, precision);
        }
        var bounds = WalkabilityGraph.searchBounds(
                ComponentFootprintUtil.bounds(plate), plateOffset,
                ComponentFootprintUtil.bounds(host), hostOffset,
                ComponentFootprintUtil.bounds(target), targetOffset,
                1);
        boolean hostOk = false;
        boolean targetOk = false;
        boolean anyLimit = false;
        for (Vec3i hostIn : hostGoals.goals()) {
            var budgetHost = WalkabilityGraph.adaptiveBudget(deck, hostIn, bounds);
            var hostOut = WalkabilityGraph.searchDetailed(occ, deck, hostIn, budgetHost, bounds);
            if (hostOut.result() == WalkabilityGraph.ReachResult.REACHABLE) {
                hostOk = true;
                break;
            }
            if (hostOut.result() == WalkabilityGraph.ReachResult.SEARCH_LIMIT) {
                anyLimit = true;
            }
        }
        for (Vec3i targetIn : targetGoals.goals()) {
            var budgetTarget = WalkabilityGraph.adaptiveBudget(deck, targetIn, bounds);
            var targetOut = WalkabilityGraph.searchDetailed(occ, deck, targetIn, budgetTarget, bounds);
            if (targetOut.result() == WalkabilityGraph.ReachResult.REACHABLE) {
                targetOk = true;
                break;
            }
            if (targetOut.result() == WalkabilityGraph.ReachResult.SEARCH_LIMIT) {
                anyLimit = true;
            }
        }
        if (hostOk && targetOk) {
            return new BridgeWalkOutcome(BridgeReach.CONNECTED, precision);
        }
        if (anyLimit) {
            return new BridgeWalkOutcome(BridgeReach.SEARCH_LIMIT, precision);
        }
        return new BridgeWalkOutcome(BridgeReach.PATH_BLOCKED, precision);
    }

    /**
     * Standable goals at {@code plateY}: prefer standable interior sample first, then region reps.
     * {@link StoreyWalkRegions.Status#SCAN_LIMIT} is preserved — not collapsed to empty goals.
     */
    public static StoreyGoals storeyGoalsPreferringInterior(
            PlannedOccupancy occ,
            Component mass,
            int plateY,
            Vec3i massOffset
    ) {
        if (occ == null || mass == null) {
            return new StoreyGoals(StoreyWalkRegions.Status.EMPTY, List.of(), 0L);
        }
        // A standalone receiving deck has no interior wall inset. Narrow bank
        // landings still provide valid bridge goals at their standable cells.
        if (mass.dimensions()!=null && mass.dimensions().height()==1 && mass.params()!=null
                && "plate".equals(mass.params().get("extrude_mode"))
                && HostedPlateOccupancy.resolveRole(mass.params())==HostedPlateOccupancy.PlateRole.TERRACE) {
            var b=ComponentFootprintUtil.bounds(mass);
            long area=(long)b.width()*b.depth();
            if(area>StoreyWalkRegions.MAX_CELLS) return new StoreyGoals(StoreyWalkRegions.Status.SCAN_LIMIT,List.of(),area);
            var goals=new ArrayList<Vec3i>();
            for(int x=b.minX();x<b.maxX();x++) for(int z=b.minZ();z<b.maxZ();z++) {
                var cell=shift(new Vec3i(x,plateY,z),massOffset);
                if(WalkabilityGraph.canStand(occ,cell)) goals.add(cell);
            }
            var remaining=new HashSet<>(goals);
            var representatives=new ArrayList<Vec3i>();
            while(!remaining.isEmpty()) {
                var seed=remaining.iterator().next(); representatives.add(seed); remaining.remove(seed);
                var queue=new ArrayDeque<Vec3i>(); queue.add(seed);
                while(!queue.isEmpty()) {
                    var cell=queue.remove();
                    for(var delta:List.of(new Vec3i(1,0,0),new Vec3i(-1,0,0),new Vec3i(0,0,1),new Vec3i(0,0,-1))) {
                        var next=new Vec3i(cell.x()+delta.x(),cell.y(),cell.z()+delta.z());
                        if(remaining.remove(next)) queue.add(next);
                    }
                }
            }
            return new StoreyGoals(representatives.isEmpty()?StoreyWalkRegions.Status.EMPTY:StoreyWalkRegions.Status.OK,representatives,area);
        }
        StoreyWalkRegions.ScanResult scan =
                CirculationAnalysisContext.scanStorey(occ, mass, plateY, massOffset);
        if (scan.status() == StoreyWalkRegions.Status.SCAN_LIMIT) {
            return new StoreyGoals(StoreyWalkRegions.Status.SCAN_LIMIT, List.of(), scan.areaCells());
        }
        List<Vec3i> reps = scan.representatives();
        if (reps.isEmpty()) {
            return new StoreyGoals(scan.status(), List.of(), scan.areaCells());
        }
        List<Vec3i> ordered = new ArrayList<>(reps.size() + 1);
        Vec3i preferred = shift(interiorFloorCell(mass, plateY), massOffset);
        if (preferred != null && WalkabilityGraph.canStand(occ, preferred)) {
            ordered.add(preferred);
        }
        for (Vec3i r : reps) {
            if (r != null && !ordered.contains(r)) {
                ordered.add(r);
            }
        }
        return new StoreyGoals(StoreyWalkRegions.Status.OK, ordered, scan.areaCells());
    }

    private static String componentId(Component mass) {
        if (mass == null || mass.params() == null) {
            return "unknown";
        }
        Object id = mass.params().get("component_id");
        return id == null ? "unknown" : String.valueOf(id);
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
