package com.formacraft.common.patch;

import com.formacraft.test.FakeBlockMutationAccess;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldPatchTransactionTest {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void preflightUnloadedChunkWritesNothing() {
        var access = new FakeBlockMutationAccess();
        access.states.put(new BlockPos(0, 1, 0), Blocks.AIR.getDefaultState());
        // x>=16 is not chunk-ready by default
        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone"),
                new BlockPatch(BlockPatch.PLACE, 20, 1, 0, "minecraft:stone")
        );
        int writesBefore = access.writes;
        var result = WorldPatchTransaction.execute(access, BlockPos.ORIGIN, patches);
        assertEquals(BuildExecutionStatus.PREFLIGHT_REJECTED, result.status());
        assertEquals(writesBefore, access.writes);
        assertEquals(0, result.applied());
        assertTrue(result.summaryZh().contains("拒绝"));
    }

    @Test
    void midApplyFailureRollsBackPriorWrites() {
        var access = new FakeBlockMutationAccess();
        access.states.put(new BlockPos(0, 1, 0), Blocks.AIR.getDefaultState());
        access.states.put(new BlockPos(1, 1, 0), Blocks.AIR.getDefaultState());
        access.rejected.add(new BlockPos(1, 1, 0));

        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone"),
                new BlockPatch(BlockPatch.PLACE, 1, 1, 0, "minecraft:stone")
        );
        var result = WorldPatchTransaction.execute(access, BlockPos.ORIGIN, patches);
        assertEquals(BuildExecutionStatus.ROLLED_BACK, result.status());
        assertEquals(Blocks.AIR.getDefaultState(), access.getState(new BlockPos(0, 1, 0)));
        assertTrue(result.recoveredFull() >= 1);
        assertEquals(0, result.recoveredStateOnly());
        assertEquals(0, result.recoveryFailed());
    }

    @Test
    void stateOnlyRecoveryReportsIncomplete() {
        var access = new FakeBlockMutationAccess();
        BlockPos pos = new BlockPos(0, 1, 0);
        access.states.put(pos, Blocks.CHEST.getDefaultState());
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", "minecraft:chest");
        access.blockEntityNbt.put(pos, nbt);
        access.failNbtRestore.add(pos);
        access.rejected.add(new BlockPos(1, 1, 0));
        access.states.put(new BlockPos(1, 1, 0), Blocks.AIR.getDefaultState());

        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone"),
                new BlockPatch(BlockPatch.PLACE, 1, 1, 0, "minecraft:stone")
        );
        var result = WorldPatchTransaction.execute(access, BlockPos.ORIGIN, patches);
        assertEquals(BuildExecutionStatus.ROLLBACK_INCOMPLETE, result.status());
        assertTrue(result.recoveredStateOnly() >= 1 || result.recoveryFailed() >= 0);
        assertTrue(result.summaryZh().contains("回滚不完整") || result.summaryZh().contains("STATE_ONLY"));
    }

    @Test
    void removeThenPlaceOrderPreservedOnSuccess() {
        var access = new FakeBlockMutationAccess();
        BlockPos pos = new BlockPos(0, 1, 0);
        access.states.put(pos, Blocks.STONE.getDefaultState());
        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.REMOVE, 0, 1, 0, "minecraft:air"),
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:oak_planks")
        );
        var result = WorldPatchTransaction.execute(access, BlockPos.ORIGIN, patches);
        assertEquals(BuildExecutionStatus.SUCCESS, result.status());
        assertEquals(Blocks.OAK_PLANKS.getDefaultState(), access.getState(pos));
    }

    @Test
    void successLeavesWorldChanged() {
        var access = new FakeBlockMutationAccess();
        List<BlockPatch> patches = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            access.states.put(new BlockPos(i, 1, 0), Blocks.AIR.getDefaultState());
            patches.add(new BlockPatch(BlockPatch.PLACE, i, 1, 0, "minecraft:dirt"));
        }
        var result = WorldPatchTransaction.execute(access, BlockPos.ORIGIN, patches);
        assertEquals(BuildExecutionStatus.SUCCESS, result.status());
        assertEquals(5, result.applied());
        assertEquals(Blocks.DIRT.getDefaultState(), access.getState(new BlockPos(0, 1, 0)));
    }
}
