package com.formacraft.server.build;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.test.FakeBlockMutationAccess;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuildTaskTest {

    @Test
    void forwardVerificationAllowsGeneratedNbtAndNeighborConnectionsButKeepsFacing() {
        var chest = Blocks.CHEST.getDefaultState();
        var nbt = new NbtCompound();
        nbt.putString("id", "minecraft:chest");
        assertTrue(BuildTask.matchesForwardTarget(
                com.formacraft.common.world.BlockSnapshot.of(chest),
                com.formacraft.common.world.BlockSnapshot.of(chest, nbt)));
        var stair = Blocks.STONE_BRICK_STAIRS.getDefaultState();
        var turned = stair.with(net.minecraft.state.property.Properties.STAIR_SHAPE,
                net.minecraft.block.enums.StairShape.INNER_LEFT);
        assertTrue(BuildTask.matchesForwardTarget(com.formacraft.common.world.BlockSnapshot.of(stair),
                com.formacraft.common.world.BlockSnapshot.of(turned)));
        assertFalse(BuildTask.matchesForwardTarget(com.formacraft.common.world.BlockSnapshot.of(stair),
                com.formacraft.common.world.BlockSnapshot.of(turned.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING,
                        stair.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING).getOpposite()))));
        var bars = Blocks.IRON_BARS.getDefaultState();
        assertTrue(BuildTask.matchesForwardTarget(com.formacraft.common.world.BlockSnapshot.of(bars),
                com.formacraft.common.world.BlockSnapshot.of(bars.with(net.minecraft.state.property.Properties.NORTH, true))));
        assertFalse(BuildTask.matchesForwardTarget(com.formacraft.common.world.BlockSnapshot.of(chest, nbt),
                com.formacraft.common.world.BlockSnapshot.of(chest)));
    }

    @BeforeAll
    static void initialize() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @BeforeEach
    @AfterEach
    void resetDeferLimits() {
        BuildTask.maxDeferAttempts = 120;
        BuildTask.deferTimeoutMs = 30_000L;
        BuildTask.deferRetryIntervalTicks = 1;
    }

    @Test
    void summaryReportsCumulativeSkipCounts() {
        BuildTask.BuildApplyResult result = new BuildTask.BuildApplyResult(356626, 34983, 26, 321617);
        assertEquals(356626, result.totalPlanned());
        assertEquals(34983, result.placed());
        assertEquals(321617, result.skippedSameState());
        assertEquals(321643, result.skippedTotal());
        assertEquals(result.totalPlanned(), result.placed() + result.skippedTotal());
        String summary = result.summaryZh();
        assertTrue(summary.contains("321617"));
        assertTrue(summary.contains("34983"));
        assertTrue(summary.contains("356626"));
        assertTrue(summary.contains("越界跳过 26"));
    }

    @Test
    void summaryKeepsUnclassifiedRemainderSeparateFromSkipped() {
        var result = new BuildTask.BuildApplyResult(10, 3, 2, 4);
        assertEquals(6, result.skippedTotal());
        assertTrue(result.summaryZh().contains("未归类 1"));
    }

    @Test
    void emptyPlanHasZeroPlacementRate() {
        var result = new BuildTask.BuildApplyResult(0, 0, 0, 0);
        assertEquals(0.0, result.placedPercent());
        assertEquals("建造完成：无计划方块", result.summaryZh());
    }

    @Test
    void tickCountsActualWritesAndRollsBackOnPermanentFail() {
        var access = new FakeBlockMutationAccess();
        var stone = Blocks.STONE.getDefaultState();
        var same = new BlockPos(1, 1, 0);
        var rejected = new BlockPos(2, 1, 0);
        access.states.put(same, stone);
        access.rejected.add(rejected);
        var blocks = List.of(
                new PlannedBlock(new BlockPos(0, 1, 0), stone),
                new PlannedBlock(same, stone),
                new PlannedBlock(rejected, stone),
                new PlannedBlock(new BlockPos(0, -1, 0), stone),
                new PlannedBlock(new BlockPos(16, 1, 0), stone),
                new PlannedBlock(new BlockPos(3, 1, 0), null));
        var structure = new GeneratedStructure(null, BlockPos.ORIGIN, "test", blocks);
        var task = new BuildTask(structure, null, access);
        BuildTask.maxDeferAttempts = 1;
        assertEquals(0, task.tick(0));
        assertEquals(2, task.tick(2));
        assertEquals(4, task.tick(10));
        assertFalse(task.isFinished());
        assertEquals(1, task.tick(10));
        drainUntilFinished(task);
        var result = task.result();
        assertEquals(0, result.placed());
        assertEquals(1, result.failedWrites());
        assertEquals(1, result.failedUnloaded());
        assertEquals(1, result.skippedIllegal());
        assertTrue(task.getAppliedChanges().isEmpty());
        assertTrue(access.writes >= 2);
        assertFalse(result.isCompletePlacement());
        assertEquals(BuildTask.Status.ROLLED_BACK, task.status());
        assertEquals(com.formacraft.common.patch.BuildExecutionStatus.ROLLED_BACK, task.mutationStatus());
        assertFalse(task.mutationOutcome().recordsHistory());
        assertTrue(result.summaryZh().contains("写入失败 1"));
        assertTrue(result.summaryZh().contains("建造失败已回滚"));
    }

    @Test
    void mutateThenRejectRecordsChangeThenRollsBackCompletely() {
        var access = new FakeBlockMutationAccess();
        var stone = Blocks.STONE.getDefaultState();
        var pos = new BlockPos(0, 1, 0);
        access.mutateThenReject.add(pos);
        var structure = new GeneratedStructure(null, BlockPos.ORIGIN, "partial-write",
                List.of(new PlannedBlock(pos, stone)));
        var task = new BuildTask(structure, null, access);
        assertEquals(1, task.tick(5));
        drainUntilFinished(task);
        assertEquals(1, task.result().failedWrites());
        assertEquals(BuildTask.Status.ROLLED_BACK, task.status());
        assertEquals(com.formacraft.common.patch.BuildExecutionStatus.ROLLED_BACK, task.mutationStatus());
        assertTrue(task.getAppliedChanges().isEmpty());
        assertEquals(Blocks.AIR.getDefaultState(), access.states.getOrDefault(pos, Blocks.AIR.getDefaultState()));
        assertFalse(task.mutationOutcome().recordsHistory());
    }

    @Test
    void incompleteRollbackKeepsResidualChangesWhenRestoreRejected() {
        var access = new FakeBlockMutationAccess();
        var stone = Blocks.STONE.getDefaultState();
        var kept = new BlockPos(0, 1, 0);
        var rejected = new BlockPos(1, 1, 0);
        access.rejected.add(rejected);
        var structure = new GeneratedStructure(null, BlockPos.ORIGIN, "residual",
                List.of(new PlannedBlock(kept, stone), new PlannedBlock(rejected, stone)));
        var task = new BuildTask(structure, null, access);
        assertEquals(1, task.tick(1));
        assertEquals(stone, access.states.get(kept));
        access.rejectRestore.add(kept);
        assertEquals(1, task.tick(10));
        drainUntilFinished(task);
        assertEquals(BuildTask.Status.PARTIAL, task.status());
        assertEquals(com.formacraft.common.patch.BuildExecutionStatus.ROLLBACK_INCOMPLETE, task.mutationStatus());
        assertEquals(1, task.getAppliedChanges().size());
        assertEquals(stone, access.states.get(kept));
        assertFalse(task.mutationOutcome().recordsHistory());
    }

    @Test
    void deferredUnloadedRetriesWhenChunkBecomesReady() {
        var access = new FakeBlockMutationAccess();
        var stone = Blocks.STONE.getDefaultState();
        var far = new BlockPos(16, 1, 0);
        access.setChunkReady(far, false);
        var structure = new GeneratedStructure(null, BlockPos.ORIGIN, "defer",
                List.of(new PlannedBlock(far, stone)));
        var task = new BuildTask(structure, null, access);
        assertEquals(1, task.tick(10));
        assertEquals(BuildTask.Status.WAITING_CHUNKS, task.status());
        assertFalse(task.isFinished());
        assertEquals(1, task.result().stillDeferred());
        access.setChunkReady(far, true);
        assertEquals(1, task.tick(10));
        assertTrue(task.isFinished());
        assertEquals(1, task.result().placed());
        assertEquals(0, task.result().failedUnloaded());
        assertEquals(stone, access.states.get(far));
    }

    @Test
    void deferredUnloadedFailsAfterMaxAttempts() {
        var access = new FakeBlockMutationAccess();
        var stone = Blocks.STONE.getDefaultState();
        var far = new BlockPos(20, 1, 0);
        access.chunkReadyOverride = p -> false;
        BuildTask.maxDeferAttempts = 2;
        var structure = new GeneratedStructure(null, BlockPos.ORIGIN, "timeout",
                List.of(new PlannedBlock(far, stone)));
        var task = new BuildTask(structure, null, access);
        assertEquals(1, task.tick(5));
        assertEquals(1, task.tick(5));
        assertEquals(1, task.tick(5));
        assertTrue(task.isFinished());
        assertEquals(1, task.result().failedUnloaded());
        assertEquals(0, task.result().placed());
        assertEquals(BuildTask.Status.FAILED, task.status());
    }

    @Test
    void perBlockExceptionIsIsolatedThenSuccessfulCellsAreRolledBack() {
        var access = new FakeBlockMutationAccess();
        var stone = Blocks.STONE.getDefaultState();
        var boom = new BlockPos(1, 1, 0);
        var ok = new BlockPos(2, 1, 0);
        access.throwOnWrite.add(boom);
        var structure = new GeneratedStructure(null, BlockPos.ORIGIN, "iso",
                List.of(new PlannedBlock(boom, stone), new PlannedBlock(ok, stone)));
        var task = new BuildTask(structure, null, access);
        assertEquals(2, task.tick(10));
        drainUntilFinished(task);
        assertEquals(1, task.result().failedWrites());
        assertEquals(0, task.result().placed());
        assertEquals(BuildTask.Status.ROLLED_BACK, task.status());
        assertEquals(Blocks.AIR.getDefaultState(), access.states.getOrDefault(ok, Blocks.AIR.getDefaultState()));
        assertTrue(task.getAppliedChanges().isEmpty());
    }

    @Test
    void cancelRollsBackAppliedChangesAcrossTicks() {
        var access = new FakeBlockMutationAccess();
        var stone = Blocks.STONE.getDefaultState();
        var first = new BlockPos(0, 1, 0);
        var blocks = List.of(
                new PlannedBlock(first, stone),
                new PlannedBlock(new BlockPos(1, 1, 0), stone),
                new PlannedBlock(new BlockPos(2, 1, 0), stone));
        var structure = new GeneratedStructure(null, BlockPos.ORIGIN, "cancel", blocks);
        var task = new BuildTask(structure, null, access);
        assertEquals(2, task.tick(2));
        assertEquals(stone, access.states.get(first));
        task.cancel();
        assertEquals(BuildTask.Status.ROLLING_BACK, task.status());
        assertFalse(task.isFinished());
        assertEquals(1, task.tick(1)); // one restore per tick
        assertFalse(task.isFinished());
        drainUntilFinished(task);
        assertEquals(BuildTask.Status.CANCELLED, task.status());
        assertEquals(com.formacraft.common.patch.BuildExecutionStatus.ROLLED_BACK, task.mutationStatus());
        assertTrue(task.getAppliedChanges().isEmpty());
        assertEquals(Blocks.AIR.getDefaultState(), access.states.getOrDefault(first, Blocks.AIR.getDefaultState()));
        assertEquals(0, task.tick(10));
        assertFalse(task.mutationOutcome().recordsHistory());
    }

    private static void drainUntilFinished(BuildTask task) {
        for (int i = 0; i < 64 && !task.isFinished(); i++) {
            task.tick(16);
        }
        assertTrue(task.isFinished(), "task should finish within drain budget, status=" + task.status());
    }

    @Test
    void cleanSuccessRecordsHistoryStatus() {
        var access = new FakeBlockMutationAccess();
        var stone = Blocks.STONE.getDefaultState();
        var pos = new BlockPos(0, 1, 0);
        var structure = new GeneratedStructure(null, BlockPos.ORIGIN, "ok",
                List.of(new PlannedBlock(pos, stone)));
        var task = new BuildTask(structure, null, access);
        assertEquals(1, task.tick(5));
        assertTrue(task.isFinished());
        assertEquals(BuildTask.Status.COMPLETED, task.status());
        assertEquals(com.formacraft.common.patch.BuildExecutionStatus.SUCCESS, task.mutationStatus());
        assertTrue(task.mutationOutcome().recordsHistory());
        assertEquals(1, task.getAppliedChanges().size());
        assertEquals(stone, access.states.get(pos));
    }

    @Test
    void nbtStateOnlyCountsAsFailedWriteButRecordsStateChange() {
        var access = new FakeBlockMutationAccess();
        var chest = Blocks.CHEST.getDefaultState();
        var pos = new BlockPos(0, 1, 0);
        access.failNbtRestore.add(pos);
        // BuildTask 正向写入通常无 NBT；用 writeSnapshot 路径直接验证 Fake 分级
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", "minecraft:chest");
        nbt.putString("Lock", "x");
        var result = access.writeSnapshot(pos, com.formacraft.common.world.BlockSnapshot.of(chest, nbt));
        assertEquals(com.formacraft.common.world.SnapshotWriteResult.STATE_ONLY, result);
        assertFalse(result.isFullSuccess());
        assertEquals(chest, access.states.get(pos));
    }
}
