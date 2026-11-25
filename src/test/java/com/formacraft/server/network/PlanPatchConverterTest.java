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
    void decorativeInvalidIsSkippedNotStone() {
        // Phase 01.5: optional décor that cannot resolve is omitted — never soft→stone.
        var result = PlanPatchConverter.convert(List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:fake_flower_xyz"),
                new BlockPatch(BlockPatch.PLACE, 1, 1, 0, "minecraft:stone")
        ), BlockPos.ORIGIN);
        assertFalse(result.hasCriticalFailure());
        assertEquals(1, result.softRecovered());
        assertEquals(1, result.blocks().size());
        assertEquals("minecraft:stone",
                net.minecraft.registry.Registries.BLOCK.getId(result.blocks().getFirst().getTargetState().getBlock()).toString());
    }

    @Test
    void unresolvedChainSoftSubstitutesHangableIronBars() {
        var result = PlanPatchConverter.convert(List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 2, 0, "minecraft:not_a_real_chain_block")
        ), BlockPos.ORIGIN);
        assertFalse(result.hasCriticalFailure());
        assertEquals(1, result.softRecovered());
        assertEquals(1, result.blocks().size());
        assertEquals("minecraft:iron_bars",
                net.minecraft.registry.Registries.BLOCK.getId(result.blocks().getFirst().getTargetState().getBlock()).toString());
    }

    @Test
    void unresolvedEndRodSoftSubstitutesSeaLanternNotStone() {
        var result = PlanPatchConverter.convert(List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 2, 0, "minecraft:fake_end_rod_xyz")
        ), BlockPos.ORIGIN);
        assertFalse(result.hasCriticalFailure());
        assertEquals(1, result.softRecovered());
        assertEquals(1, result.blocks().size());
        String id = net.minecraft.registry.Registries.BLOCK
                .getId(result.blocks().getFirst().getTargetState().getBlock()).toString();
        assertEquals("minecraft:sea_lantern", id);
        assertNotEquals("minecraft:stone", id);
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

    @Test
    void sameCellWritesCollapseToLastBeforeConversion() {
        var result = PlanPatchConverter.convert(List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone"),
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:glass"),
                new BlockPatch(BlockPatch.PLACE, 1, 1, 0, "minecraft:oak_planks")
        ), BlockPos.ORIGIN);
        assertFalse(result.hasCriticalFailure());
        assertEquals(2, result.blocks().size());
        var first = result.blocks().stream()
                .filter(b -> b.getPos().equals(new BlockPos(0, 1, 0)))
                .findFirst()
                .orElseThrow();
        assertEquals("minecraft:glass",
                net.minecraft.registry.Registries.BLOCK.getId(first.getTargetState().getBlock()).toString());
    }

    @Test
    void supersededInvalidWriteAtSameCellDoesNotFailConversion() {
        // Intermediate illegal target is overwritten by a valid final write at the same cell.
        var result = PlanPatchConverter.convert(List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:not_a_real_stair"),
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone")
        ), BlockPos.ORIGIN);
        assertFalse(result.hasCriticalFailure());
        assertEquals(0, result.criticalInvalid());
        assertEquals(1, result.blocks().size());
    }
}
