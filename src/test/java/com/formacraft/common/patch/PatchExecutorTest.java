package com.formacraft.common.patch;

import com.formacraft.test.FakeBlockMutationAccess;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PatchExecutorTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }

    @Test void countsOnlySuccessfulWritesAndAvoidsUnsafeReads() {
        var access = new FakeBlockMutationAccess();
        access.rejected.add(new BlockPos(2, 1, 0));
        var result = PatchExecutor.applyToAccess(access, BlockPos.ORIGIN, List.of(
                new BlockPatch("place", 0, 1, 0, "minecraft:stone"),
                new BlockPatch("replace", 0, 1, 0, "minecraft:stone"),
                new BlockPatch("remove", 1, 1, 0, null),
                new BlockPatch("place", 2, 1, 0, "minecraft:stone"),
                new BlockPatch("place", 0, 10, 0, "minecraft:stone"),
                new BlockPatch("place", 16, 1, 0, "minecraft:stone")));
        assertEquals(1, result.applied());
        assertEquals(2, result.skippedSameState());
        assertEquals(1, result.failedWrites());
        assertEquals(1, result.skippedWorldHeight());
        assertEquals(1, result.skippedUnloaded());
        assertEquals(2, access.writes);
        assertTrue(result.summaryZh().contains("写入失败 1"));
    }

    @Test void rejectsUnknownActionsAndMalformedPropertiesWithoutMutation() {
        var access = new FakeBlockMutationAccess();
        for (String target : List.of("minecraft:missing_block", "minecraft:stone[facing=north]",
                "minecraft:oak_stairs[facing=sideways]", "minecraft:oak_stairs[facing=east,]",
                "minecraft:oak_stairs[facing=east,facing=west]", "minecraft:oak_stairs[facing=east")) {
            assertEquals(1, PatchExecutor.applyToAccess(access, BlockPos.ORIGIN,
                    List.of(new BlockPatch("place", 0, 1, 0, target))).skippedIllegal(), target);
        }
        assertEquals(1, PatchExecutor.applyToAccess(access, BlockPos.ORIGIN,
                List.of(new BlockPatch("unknown", 0, 1, 0, "minecraft:stone"))).skippedIllegal());
        assertEquals(0, access.writes);
    }

    @Test void preservesPropertiesAndDistinguishesAirPlacementFromInvalidId() {
        var access = new FakeBlockMutationAccess();
        var result = PatchExecutor.applyToAccess(access, BlockPos.ORIGIN, List.of(
                new BlockPatch("place", 0, 1, 0, "minecraft:oak_stairs[facing=west,half=top,waterlogged=true]")));
        assertEquals(1, result.applied());
        var state = access.states.get(new BlockPos(0, 1, 0));
        assertEquals(net.minecraft.util.math.Direction.WEST, state.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING));
        assertEquals(net.minecraft.block.enums.BlockHalf.TOP, state.get(net.minecraft.state.property.Properties.BLOCK_HALF));
        assertTrue(state.get(net.minecraft.state.property.Properties.WATERLOGGED));
        assertEquals(1, PatchExecutor.applyToAccess(access, BlockPos.ORIGIN,
                List.of(new BlockPatch("replace", 0, 1, 0, "minecraft:air"))).applied());
        assertTrue(access.states.get(new BlockPos(0, 1, 0)).isAir());
    }
}
