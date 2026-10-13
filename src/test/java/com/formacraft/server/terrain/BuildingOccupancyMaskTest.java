package com.formacraft.server.terrain;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuildingOccupancyMaskTest {
    @BeforeAll
    static void init() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void lShapeDoesNotIncludeBoundingBoxCorner() {
        // L: (0,0)-(2,0) and (0,1)-(0,2); box corner (2,2) empty
        List<PlannedBlock> blocks = new ArrayList<>();
        var stone = Blocks.STONE.getDefaultState();
        for (int x = 0; x <= 2; x++) blocks.add(new PlannedBlock(new BlockPos(x, 64, 0), stone));
        for (int z = 1; z <= 2; z++) blocks.add(new PlannedBlock(new BlockPos(0, 64, z), stone));
        var mask = BuildingOccupancyMask.fromSolidBlocks(blocks);
        assertTrue(mask.contains(0, 0));
        assertTrue(mask.contains(2, 0));
        assertTrue(mask.contains(0, 2));
        assertFalse(mask.contains(2, 2));
        assertFalse(mask.contains(1, 2));
    }

    @Test
    void airBlocksDoNotOccupy() {
        var mask = BuildingOccupancyMask.fromSolidBlocks(List.of(
                new PlannedBlock(new BlockPos(1, 70, 1), Blocks.AIR.getDefaultState()),
                new PlannedBlock(new BlockPos(1, 71, 1), Blocks.STONE.getDefaultState())
        ));
        assertTrue(mask.contains(1, 1));
        assertEquals(71, mask.maxYInColumn(1, 1, 0));
    }

    @Test
    void bboxClearWouldHitEmptyColumnsButMaskDoesNot() {
        List<PlannedBlock> blocks = new ArrayList<>();
        var stone = Blocks.STONE.getDefaultState();
        for (int x = 0; x <= 2; x++) blocks.add(new PlannedBlock(new BlockPos(x, 64, 0), stone));
        for (int z = 1; z <= 2; z++) blocks.add(new PlannedBlock(new BlockPos(0, 64, z), stone));
        var mask = BuildingOccupancyMask.fromSolidBlocks(blocks);

        int clearedByBbox = 0;
        int clearedByMask = 0;
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) {
                clearedByBbox++;
                if (mask.contains(x, z)) clearedByMask++;
            }
        }
        assertEquals(9, clearedByBbox);
        assertEquals(5, clearedByMask);
        assertFalse(mask.contains(2, 2));
    }
}
