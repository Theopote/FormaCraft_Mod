package com.formacraft.common.model.request;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FormaRequestOutputFormatTest {
    @Test
    void defaultsToCanonicalPipeline() {
        FormaRequest req = new FormaRequest();
        assertEquals("llmplan", req.getOutputFormat());
        assertFalse(req.requestsLegacyBuildingSpec());
        for (String format : new String[]{null, "auto", "llmplan"}) {
            req.setOutputFormat(format);
            assertFalse(req.requestsLegacyBuildingSpec());
        }
    }

    @Test
    void onlyExplicitLegacyFormatOptsIn() {
        FormaRequest req = new FormaRequest();
        req.setUserMessage("明清官式四合院 village city");
        assertFalse(req.requestsLegacyBuildingSpec());
        req.setOutputFormat(" BuildingSpec ");
        assertTrue(req.requestsLegacyBuildingSpec());
    }
}
