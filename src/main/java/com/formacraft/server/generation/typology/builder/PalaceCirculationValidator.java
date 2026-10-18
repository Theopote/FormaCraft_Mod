package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.PlannedOccupancy;
import com.formacraft.common.generation.component.util.WalkabilityGraph;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared palace circulation checks for Builder generate-time gates and
 * PreviewPlan merge validation. Uses {@link PalaceCirculationContract} anchors
 * from actual emission — not request-param tier inference.
 */
public final class PalaceCirculationValidator {
    private PalaceCirculationValidator() {}

    public static List<TieredMountainPalaceBuilder.BuildDiagnostic> validate(
            List<BlockPatch> patches,
            PalaceCirculationContract contract
    ) {
        List<TieredMountainPalaceBuilder.BuildDiagnostic> issues = new ArrayList<>();
        if (patches == null || patches.isEmpty()) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic("EMPTY_BLOCKS", "no_patches"));
            return issues;
        }
        if (contract == null || !contract.hasAnchors()) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "CIRCULATION_CONTRACT_MISSING", "no_anchors"));
            return issues;
        }

        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches, true);
        Map<Long, BlockPatch> byCell = indexByCell(patches);

        // Strict BlockState gate for contract-critical cells before walkability.
        issues.addAll(validateStrictContractStates(byCell, contract));
        if (issues.stream().anyMatch(d ->
                d.code().startsWith("BLOCKSTATE_")
                        || d.code().startsWith("DOOR_STATE")
                        || d.code().equals("DOOR_HALF"))) {
            return issues;
        }

        long doorLeaves = countDoorLeavesStrict(patches);
        if (contract.expectedDoorLeaves() > 0 && doorLeaves < contract.expectedDoorLeaves()) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "DOOR_MISSING",
                    "expected>=" + contract.expectedDoorLeaves() + "_got=" + doorLeaves));
        }
        BlockPatch doorPatch = byCell.get(pack(
                contract.lowerDoorRel().x(), contract.lowerDoorRel().y(), contract.lowerDoorRel().z()));
        if (!isLowerDoorPatch(doorPatch)) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "DOOR_STATE",
                    "lower_door_missing_or_wrong_half_at="
                            + contract.lowerDoorRel().x() + ","
                            + contract.lowerDoorRel().y() + ","
                            + contract.lowerDoorRel().z()));
            return issues;
        }

        Vec3i start = findStandableNearDoor(occ, contract.lowerDoorRel());
        if (start == null) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "ENTRANCE_UNSTANDABLE", "no_stand_near_door"));
            return issues;
        }

        Vec3i approach = contract.approachExterior();
        int approachY = approach.y();
        if (!WalkabilityGraph.canStand(occ, approach.x(), approachY, approach.z())
                && !WalkabilityGraph.canStand(occ, approach.x(), contract.floors().isEmpty()
                ? approachY : contract.floors().getFirst().floorY(), approach.z())) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "APPROACH_UNSTANDABLE",
                    "out=" + approach.x() + "," + approachY + "," + approach.z()));
        }

        ComponentFootprintUtil.Bounds bounds = footprintBoundsExclusive(patches);

        // Floors — goals must lie inside emitted interior region.
        for (PalaceCirculationContract.FloorGoal floor : contract.floors()) {
            Vec3i goal = findStandableInRegion(occ, floor.interior(), floor.floorY(), floor.preferredAnchor());
            if (goal == null) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "FLOOR_NO_STAND", "tier" + floor.tierIndex() + "_y" + floor.floorY()));
                continue;
            }
            WalkabilityGraph.SearchBudget budget = WalkabilityGraph.adaptiveBudget(start, goal, bounds);
            WalkabilityGraph.ReachResult r = WalkabilityGraph.search(occ, start, goal, budget, bounds);
            if (r == WalkabilityGraph.ReachResult.SEARCH_LIMIT) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "FLOOR_SEARCH_LIMIT", "tier" + floor.tierIndex() + "_" + budget));
            } else if (r != WalkabilityGraph.ReachResult.REACHABLE) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "FLOOR_UNREACHABLE", "tier" + floor.tierIndex() + "_" + r.name()));
            }
        }

        // Wings — interior only (terrace/roof outside region must not substitute).
        if (contract.potala()) {
            for (PalaceCirculationContract.WingGoal wing : contract.wings()) {
                Vec3i wingGoal = findStandableInRegion(
                        occ, wing.interior(), wing.relativeAnchor().y(), wing.relativeAnchor());
                if (wingGoal == null) {
                    issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                            "WING_NO_STAND", "wing" + wing.wingIndex()));
                    continue;
                }
                WalkabilityGraph.SearchBudget budget =
                        WalkabilityGraph.adaptiveBudget(start, wingGoal, bounds);
                WalkabilityGraph.ReachResult r =
                        WalkabilityGraph.search(occ, start, wingGoal, budget, bounds);
                if (r == WalkabilityGraph.ReachResult.SEARCH_LIMIT) {
                    issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                            "WING_SEARCH_LIMIT", "wing" + wing.wingIndex()));
                } else if (r != WalkabilityGraph.ReachResult.REACHABLE) {
                    issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                            "WING_UNREACHABLE", "wing" + wing.wingIndex() + "_" + r.name()));
                }
            }
        }

        // Flights: ordered tread continuity + corridor-bounded reach (no alternate-flight bypass).
        long stairCount = countStairBlocksStrict(patches);
        if (contract.expectedStairTreads() > 0 && stairCount < contract.expectedStairTreads()) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "FLIGHT_TREAD_MISMATCH",
                    "expected=" + contract.expectedStairTreads() + "_got=" + stairCount));
        }
        for (PalaceCirculationContract.FlightAnchor flight : contract.flights()) {
            issues.addAll(validateFlightSelf(occ, byCell, flight, bounds));
        }

        for (BlockPatch p : patches) {
            if (p != null && PlannedOccupancy.isAirId(p.targetBlock())
                    && !BlockPatch.REMOVE.equals(p.action())) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "AIR_ACTION", "expected_REMOVE"));
                break;
            }
        }

        return issues;
    }

    /**
     * Per-flight integrity: each tread parses as a stair with expected facing, consecutive
     * treads form a continuous walkable chain, and lower→upper reachability is proven only
     * inside a tight corridor around this flight (other stairs cannot substitute).
     */
    private static List<TieredMountainPalaceBuilder.BuildDiagnostic> validateFlightSelf(
            PlannedOccupancy occ,
            Map<Long, BlockPatch> byCell,
            PalaceCirculationContract.FlightAnchor flight,
            ComponentFootprintUtil.Bounds buildingBounds
    ) {
        List<TieredMountainPalaceBuilder.BuildDiagnostic> issues = new ArrayList<>();
        String tag = "tier" + flight.fromTier() + "->" + flight.toTier();
        if (flight.expectedTreads() <= 0 || flight.treadCells().isEmpty()) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic("FLIGHT_EMPTY", tag));
            return issues;
        }
        if (flight.treadFacings().size() != flight.treadCells().size()) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "FLIGHT_FACING_MISMATCH", tag + "_facings=" + flight.treadFacings().size()
                    + "_treads=" + flight.treadCells().size()));
            return issues;
        }

        List<Vec3i> treads = flight.treadCells();
        List<Direction> facings = flight.treadFacings();
        for (int i = 0; i < treads.size(); i++) {
            Vec3i tread = treads.get(i);
            Direction expectedFacing = facings.get(i);
            BlockPatch p = byCell.get(pack(tread.x(), tread.y(), tread.z()));
            if (p == null || p.targetBlock() == null) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "FLIGHT_TREAD_OVERWRITE", tag + "_missing_cell=" + cellKey(tread)));
                return issues;
            }
            BlockState state = requireParse(p.targetBlock());
            if (state == null) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "BLOCKSTATE_INVALID",
                        "flight_tread=" + cellKey(tread) + "_id=" + p.targetBlock()));
                return issues;
            }
            if (!isStairState(state)) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "FLIGHT_TREAD_STATE", tag + "_cell=" + cellKey(tread)));
                return issues;
            }
            if (!state.contains(Properties.HORIZONTAL_FACING)
                    || state.get(Properties.HORIZONTAL_FACING) != expectedFacing) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "FLIGHT_TREAD_FACING",
                        tag + "_cell=" + cellKey(tread)
                                + "_expected=" + expectedFacing
                                + "_got=" + (state.contains(Properties.HORIZONTAL_FACING)
                                ? state.get(Properties.HORIZONTAL_FACING) : "none")));
                return issues;
            }
            if (state.contains(Properties.BLOCK_HALF)
                    && state.get(Properties.BLOCK_HALF) != BlockHalf.BOTTOM) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "FLIGHT_TREAD_HALF", tag + "_cell=" + cellKey(tread)));
                return issues;
            }
            if (!WalkabilityGraph.canStand(occ, tread.x(), tread.y(), tread.z())) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "FLIGHT_TREAD_UNSTANDABLE", tag + "_cell=" + cellKey(tread)));
                return issues;
            }
        }

        for (int i = 0; i < treads.size() - 1; i++) {
            Vec3i a = treads.get(i);
            Vec3i b = treads.get(i + 1);
            Direction stepFacing = facings.get(i);
            int dx = b.x() - a.x();
            int dy = b.y() - a.y();
            int dz = b.z() - a.z();
            int manhattan = Math.abs(dx) + Math.abs(dz);
            if (manhattan != 1 || Math.abs(dy) > 1) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "FLIGHT_DISCONTINUOUS",
                        tag + "_step=" + i + "_delta=" + dx + "," + dy + "," + dz));
                return issues;
            }
            // Prefer forward-in-facing for ascending runs; switchback may change facing next step.
            if (dy == 1 && (dx != stepFacing.getOffsetX() || dz != stepFacing.getOffsetZ())) {
                // Horizontal neighbor still OK if walkable (landing jog); require canTraverse.
            }
            if (!WalkabilityGraph.canTraverse(occ, a, b)) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "FLIGHT_DISCONTINUOUS",
                        tag + "_no_traverse_step=" + i));
                return issues;
            }
        }

        // Prefer standing on the first/last tread; fall back to contract endpoints nearby.
        Vec3i lower = WalkabilityGraph.canStand(occ, treads.getFirst())
                ? treads.getFirst()
                : findStandableNear(occ, flight.lowerStart().y(),
                flight.lowerStart().x(), flight.lowerStart().z(), 3);
        Vec3i upper = WalkabilityGraph.canStand(occ, flight.upperEnd())
                ? flight.upperEnd()
                : (WalkabilityGraph.canStand(occ, treads.getLast())
                ? treads.getLast()
                : findStandableNear(occ, flight.upperEnd().y(),
                flight.upperEnd().x(), flight.upperEnd().z(), 3));
        if (lower == null || upper == null) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "FLIGHT_ENDPOINT_UNSTANDABLE", tag));
            return issues;
        }

        // Corridor-bounded BFS: other flights / exterior detours outside this AABB cannot substitute.
        ComponentFootprintUtil.Bounds corridor = flightCorridorBounds(flight, 2);
        WalkabilityGraph.SearchBudget budget =
                WalkabilityGraph.adaptiveBudget(lower, upper, corridor);
        WalkabilityGraph.ReachResult r =
                WalkabilityGraph.search(occ, lower, upper, budget, corridor);
        if (r == WalkabilityGraph.ReachResult.SEARCH_LIMIT) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "FLIGHT_SEARCH_LIMIT", tag + "_corridor"));
        } else if (r != WalkabilityGraph.ReachResult.REACHABLE) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "FLIGHT_UNREACHABLE", tag + "_" + r.name()));
        }
        return issues;
    }

    private static ComponentFootprintUtil.Bounds flightCorridorBounds(
            PalaceCirculationContract.FlightAnchor flight, int margin
    ) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Vec3i t : flight.treadCells()) {
            minX = Math.min(minX, t.x());
            minY = Math.min(minY, t.y());
            minZ = Math.min(minZ, t.z());
            maxX = Math.max(maxX, t.x());
            maxY = Math.max(maxY, t.y());
            maxZ = Math.max(maxZ, t.z());
        }
        for (Vec3i t : List.of(flight.lowerStart(), flight.upperEnd())) {
            minX = Math.min(minX, t.x());
            minY = Math.min(minY, t.y());
            minZ = Math.min(minZ, t.z());
            maxX = Math.max(maxX, t.x());
            maxY = Math.max(maxY, t.y());
            maxZ = Math.max(maxZ, t.z());
        }
        int m = Math.max(0, margin);
        // Exclusive max for WalkabilityGraph.containsFloor.
        return new ComponentFootprintUtil.Bounds(
                minX - m, minY - m, minZ - m,
                maxX + m + 1, maxY + m + 2, maxZ + m + 1);
    }

    private static List<TieredMountainPalaceBuilder.BuildDiagnostic> validateStrictContractStates(
            Map<Long, BlockPatch> byCell,
            PalaceCirculationContract contract
    ) {
        List<TieredMountainPalaceBuilder.BuildDiagnostic> issues = new ArrayList<>();
        Vec3i door = contract.lowerDoorRel();
        BlockPatch lower = byCell.get(pack(door.x(), door.y(), door.z()));
        if (lower == null || lower.targetBlock() == null) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "DOOR_STATE", "missing_lower_" + cellKey(door)));
            return issues;
        }
        BlockState lowerState = requireParse(lower.targetBlock());
        if (lowerState == null) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "BLOCKSTATE_INVALID", "door_lower_id=" + lower.targetBlock()));
            return issues;
        }
        if (!isDoorState(lowerState)) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "DOOR_STATE", "not_door_at_" + cellKey(door)));
            return issues;
        }
        if (!lowerState.contains(Properties.DOUBLE_BLOCK_HALF)
                || lowerState.get(Properties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "DOOR_HALF", "expected_LOWER_at_" + cellKey(door)));
            return issues;
        }
        BlockPatch upper = byCell.get(pack(door.x(), door.y() + 1, door.z()));
        if (upper != null && upper.targetBlock() != null) {
            BlockState upperState = requireParse(upper.targetBlock());
            if (upperState == null) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "BLOCKSTATE_INVALID", "door_upper_id=" + upper.targetBlock()));
            } else if (isDoorState(upperState)
                    && upperState.contains(Properties.DOUBLE_BLOCK_HALF)
                    && upperState.get(Properties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.UPPER) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "DOOR_HALF", "expected_UPPER_at_" + door.x() + "," + (door.y() + 1) + "," + door.z()));
            }
        }

        for (PalaceCirculationContract.FlightAnchor flight : contract.flights()) {
            for (Vec3i tread : flight.treadCells()) {
                BlockPatch p = byCell.get(pack(tread.x(), tread.y(), tread.z()));
                if (p == null || p.targetBlock() == null) continue;
                if (requireParse(p.targetBlock()) == null) {
                    issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                            "BLOCKSTATE_INVALID",
                            "tread=" + cellKey(tread) + "_id=" + p.targetBlock()));
                }
            }
        }
        return issues;
    }

    // --- BlockState classifiers (STRICT for executable validation) ---

    /** Executable validation: requires parseable door BlockState. No string contains fallback. */
    public static boolean isLowerDoorPatch(BlockPatch p) {
        BlockState state = requireParse(p == null ? null : p.targetBlock());
        if (state == null || !isDoorState(state)) return false;
        if (!state.contains(Properties.DOUBLE_BLOCK_HALF)) return false;
        return state.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
    }

    public static boolean isDoorPatch(BlockPatch p) {
        BlockState state = requireParse(p == null ? null : p.targetBlock());
        return state != null && isDoorState(state);
    }

    public static boolean isStairPatch(BlockPatch p) {
        BlockState state = requireParse(p == null ? null : p.targetBlock());
        return state != null && isStairState(state);
    }

    /**
     * Loose name hint for non-executable editor UX only — never used by
     * {@link #validate} or merge integrity gates.
     */
    public static boolean looksLikeDoorHint(BlockPatch p) {
        if (p == null || p.targetBlock() == null) return false;
        if (isDoorPatch(p)) return true;
        String id = p.targetBlock().toLowerCase(Locale.ROOT);
        return id.contains("door") && !id.contains("trapdoor");
    }

    /** Loose name hint for non-executable editor UX only. */
    public static boolean looksLikeStairHint(BlockPatch p) {
        if (p == null || p.targetBlock() == null) return false;
        if (isStairPatch(p)) return true;
        return p.targetBlock().toLowerCase(Locale.ROOT).contains("stairs");
    }

    public static boolean isClearancePatch(BlockPatch p) {
        if (p == null) return false;
        if (BlockPatch.REMOVE.equals(p.action())) return true;
        if (PlannedOccupancy.isAirId(p.targetBlock())) return true;
        BlockState state = requireParse(p.targetBlock());
        return state != null && state.isAir();
    }

    public static boolean isStructuralProtected(BlockPatch p) {
        return isDoorPatch(p) || isStairPatch(p) || isClearancePatch(p);
    }

    public static boolean hasValidStairFacing(BlockPatch p) {
        BlockState state = requireParse(p == null ? null : p.targetBlock());
        return state != null && isStairState(state) && state.contains(Properties.HORIZONTAL_FACING);
    }

    public static boolean isBottomStairHalf(BlockPatch p) {
        BlockState state = requireParse(p == null ? null : p.targetBlock());
        if (state == null || !state.contains(Properties.BLOCK_HALF)) return true;
        return state.get(Properties.BLOCK_HALF) == BlockHalf.BOTTOM;
    }

    private static boolean isDoorState(BlockState state) {
        if (state == null) return false;
        if (state.isOf(Blocks.DARK_OAK_DOOR)) return true;
        String key = state.getBlock().getTranslationKey().toLowerCase(Locale.ROOT);
        return key.contains("door") && !key.contains("trapdoor");
    }

    private static boolean isStairState(BlockState state) {
        if (state == null) return false;
        if (state.isOf(Blocks.STONE_BRICK_STAIRS)) return true;
        String key = state.getBlock().getTranslationKey().toLowerCase(Locale.ROOT);
        return key.contains("stairs") && state.contains(Properties.HORIZONTAL_FACING);
    }

    /** Strict parse — null means invalid ID / illegal properties (no silent fallback). */
    private static BlockState requireParse(String blockId) {
        if (blockId == null || blockId.isBlank()) return null;
        return BlockPatchTargetResolver.parse(blockId.trim());
    }

    private static long countDoorLeavesStrict(List<BlockPatch> patches) {
        long n = 0;
        for (BlockPatch p : patches) {
            if (isDoorPatch(p)) n++;
        }
        return n;
    }

    private static long countStairBlocksStrict(List<BlockPatch> patches) {
        long n = 0;
        for (BlockPatch p : patches) {
            if (isStairPatch(p)) n++;
        }
        return n;
    }

    private static Vec3i findStandableNearDoor(PlannedOccupancy occ, Vec3i door) {
        int[][] deltas = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}, {0, 0}};
        for (int[] d : deltas) {
            int x = door.x() + d[0];
            int z = door.z() + d[1];
            if (WalkabilityGraph.canStand(occ, x, door.y(), z)) {
                return new Vec3i(x, door.y(), z);
            }
            if (WalkabilityGraph.canStand(occ, x, door.y() - 1, z)) {
                return new Vec3i(x, door.y() - 1, z);
            }
        }
        return findStandableNear(occ, door.y(), door.x(), door.z(), 4);
    }

    /**
     * Find a standable cell inside {@code region} at {@code floorY}.
     * Does not escape to roofs/terraces outside the functional space.
     */
    static Vec3i findStandableInRegion(
            PlannedOccupancy occ,
            PalaceCirculationContract.GoalRegion region,
            int floorY,
            Vec3i preferred
    ) {
        if (occ == null || region == null) return null;
        if (preferred != null
                && region.contains(preferred.x(), floorY, preferred.z())
                && WalkabilityGraph.canStand(occ, preferred.x(), floorY, preferred.z())) {
            return new Vec3i(preferred.x(), floorY, preferred.z());
        }
        int cx = preferred != null ? preferred.x() : (region.minX() + region.maxX()) / 2;
        int cz = preferred != null ? preferred.z() : (region.minZ() + region.maxZ()) / 2;
        int maxR = Math.max(region.maxX() - region.minX(), region.maxZ() - region.minZ()) + 1;
        for (int r = 0; r <= maxR; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r && r > 0) continue;
                    int x = cx + dx;
                    int z = cz + dz;
                    if (!region.contains(x, floorY, z)) continue;
                    if (WalkabilityGraph.canStand(occ, x, floorY, z)) {
                        return new Vec3i(x, floorY, z);
                    }
                }
            }
        }
        // Exhaustive scan of region slab at floorY.
        for (int x = region.minX(); x <= region.maxX(); x++) {
            for (int z = region.minZ(); z <= region.maxZ(); z++) {
                if (!region.contains(x, floorY, z)) continue;
                if (WalkabilityGraph.canStand(occ, x, floorY, z)) {
                    return new Vec3i(x, floorY, z);
                }
            }
        }
        return null;
    }

    private static Vec3i findStandableNear(PlannedOccupancy occ, int floorY, int cx, int cz, int searchRadius) {
        for (int r = 0; r <= searchRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r && r > 0) continue;
                    if (WalkabilityGraph.canStand(occ, cx + dx, floorY, cz + dz)) {
                        return new Vec3i(cx + dx, floorY, cz + dz);
                    }
                }
            }
        }
        return null;
    }

    /** Exclusive-max bounds for {@link WalkabilityGraph} floor containment. */
    private static ComponentFootprintUtil.Bounds footprintBoundsExclusive(List<BlockPatch> patches) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPatch p : patches) {
            if (p == null) continue;
            minX = Math.min(minX, p.dx());
            minY = Math.min(minY, p.dy());
            minZ = Math.min(minZ, p.dz());
            maxX = Math.max(maxX, p.dx());
            maxY = Math.max(maxY, p.dy());
            maxZ = Math.max(maxZ, p.dz());
        }
        if (minX == Integer.MAX_VALUE) {
            return new ComponentFootprintUtil.Bounds(0, 0, 0, 1, 1, 1);
        }
        return new ComponentFootprintUtil.Bounds(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    private static Map<Long, BlockPatch> indexByCell(List<BlockPatch> patches) {
        Map<Long, BlockPatch> map = new HashMap<>();
        for (BlockPatch p : patches) {
            if (p == null) continue;
            map.put(pack(p.dx(), p.dy(), p.dz()), p);
        }
        return map;
    }

    private static String cellKey(Vec3i t) {
        return t.x() + "," + t.y() + "," + t.z();
    }

    private static long pack(int dx, int dy, int dz) {
        return (((long) dx & 0x1FFFFFL) << 42)
                | (((long) dy & 0x1FFFFFL) << 21)
                | ((long) dz & 0x1FFFFFL);
    }
}
