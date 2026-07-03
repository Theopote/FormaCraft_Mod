package com.formacraft.server.build.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuildQualityReportTest {

    @Test
    void summaryZh_includesBlockCountAndRepairs() {
        BuildQualityReport report = new BuildQualityReport();
        report.stats().totalBlocks = 1420;
        report.stats().clippedByConstraint = 35;
        report.stats().repairedColumns = 12;
        report.stats().supportBlocksAdded = 48;

        String summary = report.summaryZh();
        assertTrue(summary.contains("1420"));
        assertTrue(summary.contains("35"));
        assertTrue(summary.contains("12"));
    }

    @Test
    void allowPreview_falseWhenFatal() {
        BuildQualityReport report = new BuildQualityReport();
        report.add(BuildQualitySeverity.FATAL, "EMPTY", "空结构");
        assertFalse(report.allowPreview());
        assertTrue(report.summaryZh().contains("致命"));
    }

    @Test
    void recommendApply_falseWhenError() {
        BuildQualityReport report = new BuildQualityReport();
        report.stats().totalBlocks = 100;
        report.add(BuildQualitySeverity.ERROR, "FLOATING", "大量悬空");
        assertTrue(report.allowPreview());
        assertFalse(report.recommendApply());
        assertTrue(report.summaryZh().contains("不建议"));
    }
}
