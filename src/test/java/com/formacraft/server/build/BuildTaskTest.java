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
}
