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
    void previewFailureWithoutStructureFallbackCountsAsError() {
        FormaRequest req = new FormaRequest();
        req.setOutputFormat("llmplan");
        req.setRequestText("house");

        LlmPlanRoutingMetrics.recordTaggedAttempt("p", req);
        LlmPlanRoutingMetrics.recordError("p", req, "preview_unhandled_no_structure_fallback");

        LlmPlanRoutingMetrics.Snapshot snap = LlmPlanRoutingMetrics.snapshot();
        assertEquals(1, snap.tagged());
        assertEquals(1, snap.errors());
        assertEquals(0, snap.directStructure());
    }

    @Test
    void directStructurePreviewIsSeparateFromLlmPlanErrors() {
        FormaRequest req = new FormaRequest();
        req.setOutputFormat("buildingspec");
        LlmPlanRoutingMetrics.recordDirectStructurePreview("p", req);

        LlmPlanRoutingMetrics.Snapshot snap = LlmPlanRoutingMetrics.snapshot();
        assertEquals(1, snap.directStructure());
        assertEquals(0, snap.errors());
        assertEquals(0, snap.tagged());
    }
}
