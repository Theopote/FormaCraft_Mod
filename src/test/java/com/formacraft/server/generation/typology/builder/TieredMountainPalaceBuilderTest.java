package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.generation.component.util.PlannedOccupancy;
import com.formacraft.common.generation.component.util.WalkabilityGraph;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.typology.TypologyPatchBridge;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TieredMountainPalaceBuilderTest {

    private static final BlockPos ORIGIN = new BlockPos(100, 64, 200);

    @BeforeAll
    static void init() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void defaultParamsEmitRequestedTiersAndNoDuplicateCoords() {
        GeneratedStructure structure = generate(defaultExtras(false));
        assertEquals(5, TieredMountainPalaceBuilder.parseRequestedTiers(structure.getDescription()));
        assertEquals(5, TieredMountainPalaceBuilder.parseActualTiers(structure.getDescription()));
        assertFalse(TieredMountainPalaceBuilder.parsePotalaMode(structure.getDescription()));
        assertEquals(1, TieredMountainPalaceBuilder.parseRoofCount(structure.getDescription()));
        assertNoDuplicateWorldCoords(structure);
        assertTrue(countBlock(structure, Blocks.GOLD_BLOCK) >= 9, "roof plate footprint");
        assertTrue(countBlock(structure, Blocks.DARK_OAK_DOOR) >= 2, "door leaves");
        assertTrue(countBlock(structure, Blocks.IRON_BARS) >= 4, "windows");
        assertTrue(countBlock(structure, Blocks.STONE_BRICK_STAIRS) >= 4, "stairs");
    }

    @Test
    void aggressiveInsetEarlyStopsWithDiagnostics() {
        Map<String, Object> extras = defaultExtras(false);
        extras.put("baseWidth", 24);
        extras.put("baseDepth", 20);
        extras.put("tiers", 8);
        extras.put("tierInset", 6);
        GeneratedStructure structure = generate(extras);
        int requested = TieredMountainPalaceBuilder.parseRequestedTiers(structure.getDescription());
        int actual = TieredMountainPalaceBuilder.parseActualTiers(structure.getDescription());
        assertEquals(8, requested);
        assertTrue(actual < requested, "expected early-stop actualTiers=" + actual);
        assertTrue(actual >= 1);
        assertTrue(structure.getDescription().contains("actualTiers=" + actual));
    }

    @Test
    void potalaLandmarkAddsWingsAndMultiRoofsWithRedUpper() {
        GeneratedStructure structure = generate(defaultExtras(true));
        assertTrue(TieredMountainPalaceBuilder.parsePotalaMode(structure.getDescription()));
        assertTrue(TieredMountainPalaceBuilder.parseRoofCount(structure.getDescription()) >= 3,
                "keep + two wings");
        assertTrue(countBlock(structure, Blocks.RED_TERRACOTTA) >= 20, "red upper / trim");
        assertTrue(countBlock(structure, Blocks.WHITE_TERRACOTTA) >= 20, "white walls");
        assertTrue(countBlock(structure, Blocks.STONE_BRICKS) >= 20, "stone platform");
        assertTrue(countBlock(structure, Blocks.GOLD_BLOCK) >= 9, "gold roof plate");
    }

    @Test
    void airClearanceBudgetIsFarBelowLegacyAabbClear() {
        GeneratedStructure structure = generate(defaultExtras(true));
        int air = TieredMountainPalaceBuilder.parseAirCount(structure.getDescription());
        // Legacy padded AABB ≈ 55×47×47 ≈ 121k AIR writes; scoped hollow must stay well below.
        assertTrue(air > 0, "interiors still hollowed");
        assertTrue(air < 40_000, "air=" + air + " should be ≪ legacy ~121k");
    }

    @Test
    void fourFacingsHaveEntranceAndReachTopStorey() {
        for (Direction facing : List.of(Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST)) {
            Map<String, Object> extras = defaultExtras(false);
            extras.put("facing", facing.asString());
            // Smaller footprint for faster walkability BFS
            extras.put("baseWidth", 25);
            extras.put("baseDepth", 21);
            extras.put("tiers", 3);
            extras.put("tierInset", 2);
            extras.put("tierHeight", 5);
            extras.put("platformHeight", 3);
            GeneratedStructure structure = generate(extras);
            assertTrue(countBlock(structure, Blocks.DARK_OAK_DOOR) >= 2, "facing=" + facing);
            assertReachableEntranceToUpper(structure, 3, 5, facing);
        }
    }

    @Test
    void circulationFromEntranceReachesUpperTier() {
        Map<String, Object> extras = defaultExtras(false);
        extras.put("baseWidth", 25);
        extras.put("baseDepth", 21);
        extras.put("tiers", 3);
        extras.put("tierInset", 2);
        extras.put("tierHeight", 5);
        extras.put("platformHeight", 3);
        GeneratedStructure structure = generate(extras);
        assertReachableEntranceToUpper(structure, 3, 5, Direction.SOUTH);
    }

    @Test
    void tightDepthStillHasCompleteFlightOrStairGapDiagnostic() {
        Map<String, Object> extras = defaultExtras(false);
        extras.put("baseWidth", 25);
        extras.put("baseDepth", 21); // clampOdd → 21
        extras.put("tiers", 3);
        extras.put("tierInset", 2);
        extras.put("tierHeight", 10); // rise 10 > usable south depth → east/switchback
        extras.put("platformHeight", 3);
        GeneratedStructure structure = generate(extras);
        int rise = 10;
        long stairs = countBlock(structure, Blocks.STONE_BRICK_STAIRS);
        if (TieredMountainPalaceBuilder.parseHasStairGap(structure.getDescription())) {
            // Incomplete geometry must not invent landings without enough treads
            assertTrue(stairs < rise * 2L, "truncated flights should not fabricate full landings");
        } else {
            assertTrue(stairs >= rise, "complete flight needs ≥ rise treads, stairs=" + stairs);
            assertReachableEntranceToUpper(structure, 3, 10, Direction.SOUTH);
        }
    }

    @Test
    void potalaWingsReachableFromEntrance() {
        Map<String, Object> extras = defaultExtras(true);
        extras.put("baseWidth", 49);
        extras.put("baseDepth", 41);
        extras.put("tiers", 4);
        extras.put("tierInset", 3);
        extras.put("tierHeight", 5);
        extras.put("platformHeight", 3);
        GeneratedStructure structure = generate(extras);
        assertTrue(TieredMountainPalaceBuilder.parsePotalaMode(structure.getDescription()));
        assertTrue(TieredMountainPalaceBuilder.parseRoofCount(structure.getDescription()) >= 3);

        List<BlockPatch> patches = TypologyPatchBridge.toBlockPatches(structure, ORIGIN);
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches, true);
        Vec3i door = findDoorRelative(structure);
        assertNotNull(door);
        Vec3i start = standableNeighbor(occ, door);
        assertNotNull(start);

        // Wing samples: local west/east front thirds, rotated for SOUTH (identity)
        int bw = 49;
        int bd = 41;
        int wingW = Math.min(Math.max(6, (bw - 8) / 2), Math.max(6, (int) Math.round(bw * 0.35)));
        int platformH = 3;
        int frontZ0 = bd - Math.max(6, bd / 3);
        int linkZ = (frontZ0 + bd - 1) / 2;
        Vec3i leftWing = findStandableNear(occ, platformH, wingW / 2, linkZ, 6);
        Vec3i rightWing = findStandableNear(occ, platformH, bw - 1 - wingW / 2, linkZ, 6);
        assertNotNull(leftWing, "left wing standable");
        assertNotNull(rightWing, "right wing standable");
        assertEquals(WalkabilityGraph.ReachResult.REACHABLE,
                WalkabilityGraph.search(occ, start, leftWing, 2048, null),
                "keep→left wing start=" + start + " goal=" + leftWing);
        assertEquals(WalkabilityGraph.ReachResult.REACHABLE,
                WalkabilityGraph.search(occ, start, rightWing, 2048, null),
                "keep→right wing start=" + start + " goal=" + rightWing);
    }

    @Test
    void fourFacingsHaveExteriorApproachClearance() {
        for (Direction facing : List.of(Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST)) {
            Map<String, Object> extras = defaultExtras(false);
            extras.put("facing", facing.asString());
            extras.put("baseWidth", 25);
            extras.put("baseDepth", 21);
            extras.put("tiers", 3);
            extras.put("tierInset", 2);
            extras.put("tierHeight", 5);
            extras.put("platformHeight", 3);
            GeneratedStructure structure = generate(extras);
            Vec3i door = findDoorRelative(structure);
            assertNotNull(door, "facing=" + facing);
            BlockPos out = new BlockPos(door.x(), door.y(), door.z()).offset(facing);
            boolean exteriorAir = structure.getBlocks().stream().anyMatch(b -> {
                BlockPos rel = b.getPos().subtract(ORIGIN);
                return rel.getX() == out.getX() && rel.getY() == out.getY() && rel.getZ() == out.getZ()
                        && b.getTargetState() != null && b.getTargetState().isAir();
            });
            assertTrue(exteriorAir, "exterior approach air missing facing=" + facing + " at " + out);
        }
    }

    private static void assertReachableEntranceToUpper(
            GeneratedStructure structure, int platformH, int tierHeight, Direction facing) {
        List<BlockPatch> patches = TypologyPatchBridge.toBlockPatches(structure, ORIGIN);
        PlannedOccupancy occ = PlannedOccupancy.fromPatches(patches, true);

        Vec3i door = findDoorRelative(structure);
        assertNotNull(door, "door not found facing=" + facing);
        Vec3i start = standableNeighbor(occ, door);
        assertNotNull(start, "no standable cell beside door " + door);
        assertTrue(WalkabilityGraph.canStand(occ, start.x(), start.y(), start.z()), "start=" + start);

        int topFloorY = platformH + 2 * tierHeight;
        Vec3i goal = findStandableNear(occ, topFloorY, door.x(), door.z(), 16);
        assertNotNull(goal, "no standable upper cell at y=" + topFloorY + " facing=" + facing);

        assertEquals(WalkabilityGraph.ReachResult.REACHABLE,
                WalkabilityGraph.search(occ, start, goal, 512, null),
                "facing=" + facing + " start=" + start + " goal=" + goal);
    }

    private static Vec3i findDoorRelative(GeneratedStructure structure) {
        for (PlannedBlock b : structure.getBlocks()) {
            if (b.getTargetState() != null && b.getTargetState().isOf(Blocks.DARK_OAK_DOOR)
                    && b.getTargetState().contains(net.minecraft.state.property.Properties.DOUBLE_BLOCK_HALF)
                    && b.getTargetState().get(net.minecraft.state.property.Properties.DOUBLE_BLOCK_HALF)
                    == net.minecraft.block.enums.DoubleBlockHalf.LOWER) {
                BlockPos rel = b.getPos().subtract(ORIGIN);
                return new Vec3i(rel.getX(), rel.getY(), rel.getZ());
            }
        }
        return null;
    }

    private static Vec3i standableNeighbor(PlannedOccupancy occ, Vec3i door) {
        int[][] deltas = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        for (int[] d : deltas) {
            int x = door.x() + d[0];
            int z = door.z() + d[1];
            if (WalkabilityGraph.canStand(occ, x, door.y(), z)) {
                return new Vec3i(x, door.y(), z);
            }
            // Sometimes floor is one below door sill
            if (WalkabilityGraph.canStand(occ, x, door.y() - 1, z)) {
                return new Vec3i(x, door.y() - 1, z);
            }
        }
        if (WalkabilityGraph.canStand(occ, door.x(), door.y(), door.z())) {
            return door;
        }
        return findStandableNear(occ, door.y(), door.x(), door.z(), 8);
    }

    private static Vec3i findStandableNear(PlannedOccupancy occ, int floorY, int cx, int cz, int searchRadius) {
        for (int r = 0; r <= searchRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r && r > 0) continue;
                    int x = cx + dx;
                    int z = cz + dz;
                    if (WalkabilityGraph.canStand(occ, x, floorY, z)) {
                        return new Vec3i(x, floorY, z);
                    }
                }
            }
        }
        return null;
    }

    private static Map<String, Object> defaultExtras(boolean potala) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("baseWidth", 48);
        extras.put("baseDepth", 40);
        extras.put("tiers", 5);
        extras.put("tierInset", 3);
        extras.put("tierHeight", 6);
        extras.put("platformHeight", 4);
        extras.put("facing", "SOUTH");
        if (potala) {
            extras.put("reference_landmark", TieredMountainPalaceBuilder.LANDMARK_POTALA);
        }
        return extras;
    }

    private static GeneratedStructure generate(Map<String, Object> extras) {
        BuildingSpec spec = new BuildingSpec();
        spec.setExtra(new HashMap<>(extras));
        return TieredMountainPalaceBuilder.generate(extras, ORIGIN, null, spec);
    }

    private static long countBlock(GeneratedStructure structure, net.minecraft.block.Block block) {
        return structure.getBlocks().stream()
                .filter(b -> b.getTargetState() != null && b.getTargetState().isOf(block))
                .count();
    }

    private static void assertNoDuplicateWorldCoords(GeneratedStructure structure) {
        Set<BlockPos> seen = new HashSet<>();
        for (PlannedBlock b : structure.getBlocks()) {
            assertTrue(seen.add(b.getPos().toImmutable()), "duplicate at " + b.getPos());
        }
    }
}
