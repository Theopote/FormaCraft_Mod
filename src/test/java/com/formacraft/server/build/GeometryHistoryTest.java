package com.formacraft.server.build;

import com.formacraft.server.build.quality.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GeometryHistoryTest {
    @Test void finalReportPreservesEvidenceWithoutReactivatingResolvedError() {
        var initial = new BuildQualityReport();
        initial.add(new BuildQualityIssue(BuildQualitySeverity.ERROR, "FLOATING", "floating",
                "initial", 12, List.of(new BuildQualityIssue.Position(1,80,2))));
        var delivered = new BuildQualityReport();
        delivered.add(BuildQualitySeverity.WARNING, "DIM_WIDTH", "width");
        var result = BuildPreviewPipeline.finalizedReport(initial, delivered);
        assertTrue(result.recommendApply());
        assertEquals(1, result.activeDiagnostics().size());
        assertEquals("final", result.activeDiagnostics().getFirst().phase());
        var history = result.issues().stream().filter(i -> i.code().equals("INITIAL_FLOATING")).findFirst().orElseThrow();
        assertEquals("initial", history.phase());
        assertEquals(12, history.affectedCount());
        assertEquals(new BuildQualityIssue.Position(1,80,2), history.positions().getFirst());
    }

    @Test void laterFindingsInheritCurrentReportPhase() {
        var report = new BuildQualityReport();
        report.phase("final");
        report.add(BuildQualitySeverity.FATAL, "E_FINAL_QUALITY_FAILED", "failed");
        assertEquals("final", report.activeDiagnostics().getFirst().phase());
        assertFalse(report.allowPreview());
    }

    @Test void residualErrorStillRequiresExplicitForce() {
        var initial = new BuildQualityReport();
        var delivered = new BuildQualityReport();
        delivered.add(new BuildQualityIssue(BuildQualitySeverity.ERROR, "FLOATING", "residual",
                "unspecified", 12, List.of()));
        var result = BuildPreviewPipeline.finalizedReport(initial, delivered);
        assertEquals("final", result.activeDiagnostics().getFirst().phase());
        assertEquals(BuildQualityGate.Decision.REQUIRE_FORCE, BuildQualityGate.decide(result, false));
    }
}
