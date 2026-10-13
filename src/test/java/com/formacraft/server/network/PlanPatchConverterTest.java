package com.formacraft.server.network;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanPatchConverterTest {
    @BeforeAll
    static void init() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void criticalInvalidStructuralTargetFails() {
        var result = PlanPatchConverter.convert(List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:not_a_real_stair"),
                new BlockPatch(BlockPatch.PLACE, 1, 1, 0, "minecraft:stone")
        ), BlockPos.ORIGIN);
        assertTrue(result.hasCriticalFailure());
        assertEquals(1, result.criticalInvalid());
        assertEquals(1, result.blocks().size());
    }

    @Test
    void decorativeInvalidCanSoftRecover() {
        var result = PlanPatchConverter.convert(List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:fake_flower_xyz"),
                new BlockPatch(BlockPatch.PLACE, 1, 1, 0, "minecraft:stone")
        ), BlockPos.ORIGIN);
        assertFalse(result.hasCriticalFailure());
        assertEquals(1, result.softRecovered());
        assertEquals(2, result.blocks().size());
    }

    @Test
    void removeAlwaysResolvesToAirNotCritical() {
        // BlockPatchTargetResolver maps REMOVE → AIR regardless of targetBlock
        var result = PlanPatchConverter.convert(List.of(
                new BlockPatch(BlockPatch.REMOVE, 0, 1, 0, "minecraft:unknown_block_xyz"),
                new BlockPatch(BlockPatch.PLACE, 1, 1, 0, "minecraft:stone")
        ), BlockPos.ORIGIN);
        assertFalse(result.hasCriticalFailure());
        assertEquals(0, result.criticalInvalid());
        assertEquals(2, result.blocks().size());
        assertTrue(result.blocks().get(0).getTargetState().isAir());
    }
}
