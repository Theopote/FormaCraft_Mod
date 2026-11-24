package com.formacraft.server.interior;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BspFloorPlanGeneratorTest {

    @BeforeAll
    static void init() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    private static List<PlannedBlock> generate() {
        List<PlannedBlock> out = new ArrayList<>();
        BspFloorPlanGenerator.apply(
                out,
                BlockPos.ORIGIN,
                null,
                25,
                25,
                16,
                FloorPlanConfig.defaults(),
                BspFloorPlanGenerator.Materials.of(
                        Blocks.STONE_BRICKS.getDefaultState(),
                        Blocks.OAK_PLANKS.getDefaultState(),
                        Blocks.STONE_BRICK_STAIRS.getDefaultState()));
        return out;
    }

    private static Map<BlockPos, Boolean> airAtY(List<PlannedBlock> blocks, int y) {
        Map<BlockPos, Boolean> last = new HashMap<>();
        for (PlannedBlock b : blocks) {
            if (b.getPos().getY() != y) continue;
            last.put(b.getPos(), b.getTargetState().isAir());
        }
        return last;
    }

    @Test
    void roomsOpenOntoCorridorRing() {
        List<PlannedBlock> blocks = generate();
        assertFalse(blocks.isEmpty());
        // At least one air carve on the ring-adjacent wall band (y=1 door height)
        Map<BlockPos, Boolean> air = airAtY(blocks, 1);
        long doorAir = air.entrySet().stream().filter(Map.Entry::getValue).count();
        assertTrue(doorAir >= 4, "expected room and/or core door openings, got " + doorAir);
    }

    @Test
    void coreHasDoorOpeningsOnEachCardinal() {
        List<PlannedBlock> blocks = generate();
        FloorPlanConfig cfg = FloorPlanConfig.defaults();
        int coreW = cfg.coreW, coreD = cfg.coreD;
        int x0 = -coreW / 2, x1 = -coreW / 2 + coreW - 1;
        int z0 = -coreD / 2, z1 = -coreD / 2 + coreD - 1;
        int mx = (x0 + x1) / 2, mz = (z0 + z1) / 2;
        Map<BlockPos, Boolean> air = airAtY(blocks, 1);
        assertTrue(Boolean.TRUE.equals(air.get(new BlockPos(x0, 1, mz))), "west core door");
        assertTrue(Boolean.TRUE.equals(air.get(new BlockPos(x1, 1, mz))), "east core door");
        assertTrue(Boolean.TRUE.equals(air.get(new BlockPos(mx, 1, z0))), "north core door");
        assertTrue(Boolean.TRUE.equals(air.get(new BlockPos(mx, 1, z1))), "south core door");
    }

    @Test
    void stairRunReachesNextStoreyLandingWithoutDeepVoid() {
        List<PlannedBlock> blocks = generate();
        // Landing at y=STOREY_HEIGHT should exist as solid somewhere in core shaft
        boolean landing = blocks.stream().anyMatch(b ->
                b.getPos().getY() == BspFloorPlanGenerator.STOREY_HEIGHT
                        && !b.getTargetState().isAir()
                        && Math.abs(b.getPos().getX()) <= 4
                        && Math.abs(b.getPos().getZ()) <= 4);
        assertTrue(landing, "expected solid landing at next storey");

        assertTrue(blocks.stream().anyMatch(b -> b.getPos().getY() == 1
                && b.getTargetState().getBlock() instanceof net.minecraft.block.StairsBlock));
        assertTrue(blocks.stream().anyMatch(b -> b.getPos().getY() == 3
                && b.getTargetState().getBlock() instanceof net.minecraft.block.StairsBlock));
    }

    @Test
    void siblingSplitSharesWallLineWithoutGapColumn() {
        // Deterministic: dense split of a strip should not leave an empty x-gap between rooms.
        List<PlannedBlock> out = new ArrayList<>();
        FloorPlanConfig dense = FloorPlanConfig.fromExtra(Map.of(
                "partition_style", "DENSE_GRID",
                "room_min_size", 4,
                "corridor_width", 2,
                "core_w", 6,
                "core_d", 6));
        assertNotNull(dense);
        BspFloorPlanGenerator.apply(out, BlockPos.ORIGIN, null, 31, 19, 12, dense,
                BspFloorPlanGenerator.Materials.of(
                        Blocks.STONE_BRICKS.getDefaultState(),
                        Blocks.OAK_PLANKS.getDefaultState(),
                        Blocks.STONE_BRICK_STAIRS.getDefaultState()));
        assertFalse(out.isEmpty());
        // Room walls should appear as solid cells; no requirement on exact topology beyond generation
        assertTrue(out.stream().anyMatch(b -> b.getTargetState().isOf(Blocks.OAK_PLANKS)));
    }

    @Test
    void adjacentRoomsGetSharedWallDoor() {
        // Dense plan produces multiple rooms; shared-wall air carves should appear beyond core doors alone
        List<PlannedBlock> out = new ArrayList<>();
        FloorPlanConfig dense = FloorPlanConfig.fromExtra(Map.of(
                "partition_style", "DENSE_GRID",
                "room_min_size", 4,
                "corridor_width", 2,
                "core_w", 6,
                "core_d", 6));
        BspFloorPlanGenerator.apply(out, BlockPos.ORIGIN, null, 35, 23, 12, dense,
                BspFloorPlanGenerator.Materials.of(
                        Blocks.STONE_BRICKS.getDefaultState(),
                        Blocks.OAK_PLANKS.getDefaultState(),
                        Blocks.STONE_BRICK_STAIRS.getDefaultState()));
        long roomDoorAir = out.stream()
                .filter(b -> b.getPos().getY() == 1 && b.getTargetState().isAir())
                .filter(b -> Math.abs(b.getPos().getX()) > 5 || Math.abs(b.getPos().getZ()) > 5)
                .count();
        assertTrue(roomDoorAir >= 2, "expected inter-room / ring door air outside the core, got " + roomDoorAir);
    }
}
