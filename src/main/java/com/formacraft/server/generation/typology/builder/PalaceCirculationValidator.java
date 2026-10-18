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

        // --- Door state semantics ---
        long doorLeaves = countDoorLeaves(patches);
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
            return issues; // cannot proceed without entrance
        }

        Vec3i start = findStandableNearDoor(occ, contract.lowerDoorRel());
        if (start == null) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "ENTRANCE_UNSTANDABLE", "no_stand_near_door"));
            return issues;
        }

        // Exterior approach
        Vec3i approach = contract.approachExterior();
        int approachY = approach.y();
        if (!WalkabilityGraph.canStand(occ, approach.x(), approachY, approach.z())
                && !WalkabilityGraph.canStand(occ, approach.x(), contract.floors().isEmpty()
                ? approachY : contract.floors().getFirst().floorY(), approach.z())) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "APPROACH_UNSTANDABLE",
                    "out=" + approach.x() + "," + approachY + "," + approach.z()));
        }

        ComponentFootprintUtil.Bounds bounds = footprintBounds(patches);

        // Floors (actual generated)
        for (PalaceCirculationContract.FloorGoal floor : contract.floors()) {
            Vec3i goal = findStandableNear(
                    occ, floor.floorY(),
                    floor.preferredAnchor().x(), floor.preferredAnchor().z(), 12);
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

        // Wings (Potala)
        if (contract.potala()) {
            for (PalaceCirculationContract.WingGoal wing : contract.wings()) {
                Vec3i anchor = wing.relativeAnchor();
                Vec3i wingGoal = findStandableNear(occ, anchor.y(), anchor.x(), anchor.z(), 8);
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

        // Flights: tread survival + end-to-end connectivity
        long stairCount = countStairBlocks(patches);
        if (contract.expectedStairTreads() > 0 && stairCount < contract.expectedStairTreads()) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "FLIGHT_TREAD_MISMATCH",
                    "expected=" + contract.expectedStairTreads() + "_got=" + stairCount));
        }
        for (PalaceCirculationContract.FlightAnchor flight : contract.flights()) {
            if (flight.expectedTreads() <= 0 || flight.treadCells().isEmpty()) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "FLIGHT_EMPTY", "tier" + flight.fromTier() + "->" + flight.toTier()));
                continue;
            }
            int missingTreads = 0;
            for (Vec3i tread : flight.treadCells()) {
                BlockPatch p = byCell.get(pack(tread.x(), tread.y(), tread.z()));
                if (!isStairPatch(p)) {
                    missingTreads++;
                }
            }
            if (missingTreads > 0) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "FLIGHT_TREAD_OVERWRITE",
                        "tier" + flight.fromTier() + "->" + flight.toTier()
                                + "_missing=" + missingTreads));
                continue;
            }
            Vec3i lower = findStandableNear(
                    occ, flight.lowerStart().y(),
                    flight.lowerStart().x(), flight.lowerStart().z(), 4);
            Vec3i upper = findStandableNear(
                    occ, flight.upperEnd().y(),
                    flight.upperEnd().x(), flight.upperEnd().z(), 4);
            if (lower == null || upper == null) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "FLIGHT_ENDPOINT_UNSTANDABLE",
                        "tier" + flight.fromTier() + "->" + flight.toTier()));
                continue;
            }
            WalkabilityGraph.SearchBudget budget =
                    WalkabilityGraph.adaptiveBudget(lower, upper, bounds);
            WalkabilityGraph.ReachResult r =
                    WalkabilityGraph.search(occ, lower, upper, budget, bounds);
            if (r == WalkabilityGraph.ReachResult.SEARCH_LIMIT) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "FLIGHT_SEARCH_LIMIT",
                        "tier" + flight.fromTier() + "->" + flight.toTier()));
            } else if (r != WalkabilityGraph.ReachResult.REACHABLE) {
                issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                        "FLIGHT_UNREACHABLE",
                        "tier" + flight.fromTier() + "->" + flight.toTier() + "_" + r.name()));
            }
        }

        // AIR identity: air cells should use REMOVE
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

    // --- BlockState-aware patch classifiers ---

    public static boolean isLowerDoorPatch(BlockPatch p) {
        if (p == null || p.targetBlock() == null) return false;
        BlockState state = tryParse(p.targetBlock());
        if (state != null) {
            if (!state.contains(Properties.DOUBLE_BLOCK_HALF)) {
                // Single-block door-like — accept if door block
                return state.isOf(Blocks.DARK_OAK_DOOR)
                        || state.getBlock().getTranslationKey().contains("door");
            }
            return state.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                    && (state.isOf(Blocks.DARK_OAK_DOOR)
                    || state.getBlock().getTranslationKey().contains("door"));
        }
        String id = p.targetBlock().toLowerCase(Locale.ROOT);
        return id.contains("door") && !id.contains("trapdoor");
    }

    public static boolean isDoorPatch(BlockPatch p) {
        if (p == null || p.targetBlock() == null) return false;
        BlockState state = tryParse(p.targetBlock());
        if (state != null) {
            if (state.isOf(Blocks.DARK_OAK_DOOR)) return true;
            String key = state.getBlock().getTranslationKey().toLowerCase(Locale.ROOT);
            return key.contains("door") && !key.contains("trapdoor");
        }
        String id = p.targetBlock().toLowerCase(Locale.ROOT);
        return id.contains("door") && !id.contains("trapdoor");
    }

    public static boolean isStairPatch(BlockPatch p) {
        if (p == null || p.targetBlock() == null) return false;
        BlockState state = tryParse(p.targetBlock());
        if (state != null) {
            if (state.contains(Properties.HORIZONTAL_FACING)
                    && state.getBlock().getTranslationKey().toLowerCase(Locale.ROOT).contains("stairs")) {
                return true;
            }
            if (state.isOf(Blocks.STONE_BRICK_STAIRS)) return true;
        }
        return p.targetBlock().toLowerCase(Locale.ROOT).contains("stairs");
    }

    public static boolean isClearancePatch(BlockPatch p) {
        if (p == null) return false;
        if (BlockPatch.REMOVE.equals(p.action())) return true;
        if (PlannedOccupancy.isAirId(p.targetBlock())) return true;
        BlockState state = tryParse(p.targetBlock());
        return state != null && state.isAir();
    }

    public static boolean isStructuralProtected(BlockPatch p) {
        return isDoorPatch(p) || isStairPatch(p) || isClearancePatch(p);
    }

    /** Stair with facing property present (layout-sensitive). */
    public static boolean hasValidStairFacing(BlockPatch p) {
        if (!isStairPatch(p)) return false;
        BlockState state = tryParse(p == null ? null : p.targetBlock());
        return state != null && state.contains(Properties.HORIZONTAL_FACING);
    }

    public static boolean isBottomStairHalf(BlockPatch p) {
        BlockState state = tryParse(p == null ? null : p.targetBlock());
        if (state == null || !state.contains(Properties.BLOCK_HALF)) return true;
        return state.get(Properties.BLOCK_HALF) == BlockHalf.BOTTOM;
    }

    private static BlockState tryParse(String blockId) {
        if (blockId == null || blockId.isBlank()) return null;
        try {
            return BlockPatchTargetResolver.parse(blockId.trim());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long countDoorLeaves(List<BlockPatch> patches) {
        long n = 0;
        for (BlockPatch p : patches) {
            if (isDoorPatch(p)) n++;
        }
        return n;
    }

    private static long countStairBlocks(List<BlockPatch> patches) {
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
        return findStandableNear(occ, door.y(), door.x(), door.z(), 8);
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

    private static ComponentFootprintUtil.Bounds footprintBounds(List<BlockPatch> patches) {
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
        return new ComponentFootprintUtil.Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static Map<Long, BlockPatch> indexByCell(List<BlockPatch> patches) {
        Map<Long, BlockPatch> map = new HashMap<>();
        for (BlockPatch p : patches) {
            if (p == null) continue;
            map.put(pack(p.dx(), p.dy(), p.dz()), p);
        }
        return map;
    }

    private static long pack(int dx, int dy, int dz) {
        return (((long) dx & 0x1FFFFFL) << 42)
                | (((long) dy & 0x1FFFFFL) << 21)
                | ((long) dz & 0x1FFFFFL);
    }
}
