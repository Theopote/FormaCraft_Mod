package com.formacraft.common.network.metrics;

import com.formacraft.common.model.request.FormaRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmPlanRoutingMetricsTest {

    @BeforeEach
    void reset() {
        LlmPlanRoutingMetrics.resetForTest();
    }

    @Test
    void previewFailureWithoutStructureFallbackCountsAsErrorNotStructureAfterFallback() {
        FormaRequest req = new FormaRequest();
        req.setOutputFormat("llmplan");
        req.setRequestText("house");

        LlmPlanRoutingMetrics.recordTaggedAttempt("p", req);
        // Mirrors BuildRequestProcessor when tryBuildPreview returns false (no structure fallback).
        LlmPlanRoutingMetrics.recordError("p", req, "preview_unhandled_no_structure_fallback");

        LlmPlanRoutingMetrics.Snapshot snap = LlmPlanRoutingMetrics.snapshot();
        assertEquals(1, snap.tagged());
        assertEquals(1, snap.errors());
        assertEquals(0, snap.fallback());
        assertEquals(0, snap.structureAfterFallback());
    }

    @Test
    void structureAfterFallbackOnlyIncrementsWhenExplicitlyRecordedAfterRealFallback() {
        FormaRequest req = new FormaRequest();
        req.setOutputFormat("llmplan");

        LlmPlanRoutingMetrics.recordFallback(LlmPlanRoutingMetrics.FallbackReason.EMPTY_OUTPUT, "p", req);
        LlmPlanRoutingMetrics.recordStructureAfterFallback("p", req);

        LlmPlanRoutingMetrics.Snapshot snap = LlmPlanRoutingMetrics.snapshot();
        assertEquals(1, snap.fallback());
        assertEquals(1, snap.structureAfterFallback());
        assertEquals(0, snap.errors());
    }

    @Test
    void directStructurePreviewIsSeparateFromFallback() {
        FormaRequest req = new FormaRequest();
        req.setOutputFormat("buildingspec");
        LlmPlanRoutingMetrics.recordDirectStructurePreview("p", req);

        LlmPlanRoutingMetrics.Snapshot snap = LlmPlanRoutingMetrics.snapshot();
        assertEquals(1, snap.directStructure());
        assertEquals(0, snap.structureAfterFallback());
        assertEquals(0, snap.fallback());
    }
}
