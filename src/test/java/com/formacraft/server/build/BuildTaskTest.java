package com.formacraft.server.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildTaskTest {

    @Test
    void summaryReportsCumulativeSkipCounts() {
        BuildTask.BuildApplyResult result = new BuildTask.BuildApplyResult(356626, 34983, 0, 321617);
        assertEquals(356626, result.totalPlanned());
        assertEquals(34983, result.placed());
        assertEquals(321617, result.skippedSameState());
        assertEquals(356600, result.skippedTotal());
        String summary = result.summaryZh();
        assertTrue(summary.contains("321617"));
        assertTrue(summary.contains("34983"));
        assertTrue(summary.contains("356626"));
    }
}
