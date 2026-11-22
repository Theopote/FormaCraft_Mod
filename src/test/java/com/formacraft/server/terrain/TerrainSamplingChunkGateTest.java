package com.formacraft.server.terrain;

import com.formacraft.server.build.BuildReport;
import com.formacraft.server.build.BuildReportContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerrainSamplingChunkGateTest {

    @Test
    void nullWorldDoesNotThrowAndUsesFallback() {
        assertEquals(TerrainSampling.FALLBACK_Y, TerrainSampling.placementY(null, 0, 0));
        assertTrue(TerrainSampling.placementYIfReady(null, 0, 0).isEmpty());
        assertFalse(TerrainSampling.isColumnReady(null, 0, 0));
        assertNull(TerrainSampling.blockStateIfReady(null, null));
    }

    @Test
    void unloadedColumnNotesAppearInBuildReport() {
        BuildReport report = BuildReportContext.withNewReport(() -> {
            TerrainSampling.noteUnloadedColumn();
            TerrainSampling.noteUnloadedColumn();
        });
        assertTrue(report.summaryZh().contains("未加载区块"));
        assertTrue(report.summaryZh().contains("2"));
    }
}
