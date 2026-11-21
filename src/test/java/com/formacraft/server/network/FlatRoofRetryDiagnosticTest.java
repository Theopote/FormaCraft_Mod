package com.formacraft.server.network;

import com.formacraft.common.llm.dto.CapabilityGap;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FlatRoofRetryDiagnosticTest {
    @Test void unsuccessfulRetryKeepsTheOriginalCoverageEvidence() {
        var original = new CapabilityGap("E_FLAT_ROOF_HOST_COVERAGE", "Original coverage gap", "components[]",
                List.of("Preserve the courtyard"), Map.of("host_id", "hall", "uncovered_count", 18));
        var retry = new CapabilityGap("E_PATCH_CONFLICT", "Candidate overlaps another mass", "components[]", List.of());
        var result = LlmPlanPreviewBuilder.withRoofRetryFailure(original, retry);
        assertEquals(original.code(), result.code());
        assertEquals(original.summary(), result.summary());
        assertEquals(original.suggestions(), result.suggestions());
        assertEquals("hall", result.details().get("host_id"));
        assertEquals(18, result.details().get("uncovered_count"));
        assertSame(retry, result.details().get("repair_retry_failure"));
        assertFalse(original.details().containsKey("repair_retry_failure"));
        assertSame(retry, LlmPlanPreviewBuilder.withRoofRetryFailure(null, retry));
    }
}
