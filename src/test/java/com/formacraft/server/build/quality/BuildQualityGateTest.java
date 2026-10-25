package com.formacraft.server.build.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuildQualityGateTest {

    @Test
    void nullReportAllows() {
        assertEquals(BuildQualityGate.Decision.ALLOW, BuildQualityGate.decide((BuildQualityReport) null, false));
    }

    @Test
    void fatalDeniesEvenWithForce() {
        BuildQualityReport r = new BuildQualityReport();
        r.add(BuildQualitySeverity.FATAL, "X", "fatal");
        assertEquals(BuildQualityGate.Decision.DENY_FATAL, BuildQualityGate.decide(r, false));
        assertEquals(BuildQualityGate.Decision.DENY_FATAL, BuildQualityGate.decide(r, true));
    }

    @Test
    void errorRequiresForce() {
        BuildQualityReport r = new BuildQualityReport();
        r.add(BuildQualitySeverity.ERROR, "E", "error");
        assertEquals(BuildQualityGate.Decision.REQUIRE_FORCE, BuildQualityGate.decide(r, false));
        assertEquals(BuildQualityGate.Decision.ALLOW, BuildQualityGate.decide(r, true));
    }

    @Test
    void snapshotMatchesReport() {
        BuildQualityReport r = new BuildQualityReport();
        r.add(BuildQualitySeverity.ERROR, "E", "bad");
        var snap = BuildQualityGate.QualitySnapshot.from(r);
        assertTrue(snap.hasError());
        assertFalse(snap.hasFatal());
        assertEquals(BuildQualityGate.Decision.REQUIRE_FORCE, BuildQualityGate.decide(snap, false));
        assertEquals(BuildQualityGate.Decision.ALLOW, BuildQualityGate.decide(snap, true));
    }

    @Test
    void residualFloatingAboveHardGateIsFatalEvenWithForce() {
        BuildQualityReport r = new BuildQualityReport();
        r.stats().floatingColumns = BuildQualityReport.floatingFatalColumns();
        com.formacraft.server.build.BuildPreviewPipeline.escalateResidualFloating(r);
        assertTrue(r.hasFatal());
        assertTrue(r.issues().stream().anyMatch(i -> "FLOATING_RESIDUAL".equals(i.code())));
        assertEquals(BuildQualityGate.Decision.DENY_FATAL, BuildQualityGate.decide(r, true));
    }

    @Test
    void residualFloatingBelowHardGateStaysForceable() {
        BuildQualityReport r = new BuildQualityReport();
        r.add(BuildQualitySeverity.ERROR, "FLOATING", "mild");
        r.stats().floatingColumns = BuildQualityReport.floatingFatalColumns() - 1;
        com.formacraft.server.build.BuildPreviewPipeline.escalateResidualFloating(r);
        assertFalse(r.hasFatal());
        assertEquals(BuildQualityGate.Decision.ALLOW, BuildQualityGate.decide(r, true));
    }
}
