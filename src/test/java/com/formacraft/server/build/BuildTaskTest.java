package com.formacraft.server.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildTaskTest {

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
    void tickCountsActualWritesAndRetainsOnlySuccessfulUndoChanges() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        var access = new com.formacraft.test.FakeBlockMutationAccess();
        var stone = net.minecraft.block.Blocks.STONE.getDefaultState();
        var same = new net.minecraft.util.math.BlockPos(1, 1, 0);
        var rejected = new net.minecraft.util.math.BlockPos(2, 1, 0);
        access.states.put(same, stone);
        access.rejected.add(rejected);
        var blocks = java.util.List.of(
                new com.formacraft.common.build.PlannedBlock(new net.minecraft.util.math.BlockPos(0, 1, 0), stone),
                new com.formacraft.common.build.PlannedBlock(same, stone),
                new com.formacraft.common.build.PlannedBlock(rejected, stone),
                new com.formacraft.common.build.PlannedBlock(new net.minecraft.util.math.BlockPos(0, -1, 0), stone),
                new com.formacraft.common.build.PlannedBlock(new net.minecraft.util.math.BlockPos(16, 1, 0), stone),
                new com.formacraft.common.build.PlannedBlock(new net.minecraft.util.math.BlockPos(3, 1, 0), null));
        var structure = new com.formacraft.common.build.GeneratedStructure(null,
                net.minecraft.util.math.BlockPos.ORIGIN, "test", blocks);
        var task = new BuildTask(structure, null, access);
        assertEquals(0, task.tick(0));
        assertEquals(2, task.tick(2));
        assertEquals(4, task.tick(10));
        assertTrue(task.isFinished());
        var result = task.result();
        assertEquals(1, result.placed());
        assertEquals(1, result.failedWrites());
        assertEquals(4, result.skippedTotal());
        assertEquals(1, result.skippedUnloaded());
        assertEquals(1, result.skippedIllegal());
        assertEquals(1, task.getAppliedChanges().size());
        assertEquals(2, access.writes);
        assertTrue(!result.isCompletePlacement());
        assertTrue(result.summaryZh().contains("写入失败 1"));
        assertTrue(!result.summaryZh().contains("未归类"));
    }
}
