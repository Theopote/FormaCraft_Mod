package com.formacraft.common.network.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandmarkRoutingMetricsTest {

    @Test
    void detectsApproxMatchForSagradaFamilia() {
        assertTrue(LandmarkRoutingMetrics.isApproximateLandmarkMatch(
                "生成圣家族大教堂",
                "gothic_cathedral"
        ));
    }

    @Test
    void acceptsExplicitGothicCathedralName() {
        assertFalse(LandmarkRoutingMetrics.isApproximateLandmarkMatch(
                "哥特大教堂",
                "gothic_cathedral"
        ));
    }
}
