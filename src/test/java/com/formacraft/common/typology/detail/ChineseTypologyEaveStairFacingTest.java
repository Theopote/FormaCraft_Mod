package com.formacraft.common.typology.detail;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChineseTypologyEaveStairFacingTest {

    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void squareRingStairsFaceInwardTowardShaft() {
        List<PlannedBlock> blocks = new ArrayList<>();
        ChineseTypologyDetailUtil.ringSquare(
                blocks, new BlockPos(0, 64, 0), 3, 70,
                Blocks.BLACKSTONE_STAIRS.getDefaultState(), null);
        // South edge (z=+3): high side faces north (inward).
        PlannedBlock south = blocks.stream()
                .filter(b -> b.getPos().getX() == 0 && b.getPos().getZ() == 3)
                .findFirst().orElseThrow();
        assertEquals(Direction.NORTH, south.getTargetState().get(StairsBlock.FACING));
        // East edge (x=+3): high side faces west.
        PlannedBlock east = blocks.stream()
                .filter(b -> b.getPos().getX() == 3 && b.getPos().getZ() == 0)
                .findFirst().orElseThrow();
        assertEquals(Direction.WEST, east.getTargetState().get(StairsBlock.FACING));
        // Not all stairs share one facing.
        long distinct = blocks.stream()
                .map(b -> b.getTargetState().get(StairsBlock.FACING))
                .distinct()
                .count();
        assertTrue(distinct >= 4, "eave stairs must face four directions, got " + distinct);
    }
}
