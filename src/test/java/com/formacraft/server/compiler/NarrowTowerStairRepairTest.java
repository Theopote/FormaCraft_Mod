package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Thin facade strips must not be hardened like narrow tower shafts.
 */
class NarrowTowerStairRepairTest {

    @Test
    void doesNotDisableThinFacadeWindowStrip() {
        Component facade = new Component(
                "FACADE_WINDOWS",
                "villa_1",
                new Vec3i(-5, 1, 0),
                new Dimensions(10, 1, 4),
                List.of(),
                Map.of("window_ratio", 0.4, "window_aspect", "vertical_strip", "rhythm", "regular")
        );
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .components(List.of(facade))
                .build();

        LlmPlan repaired = NarrowTowerStairRepair.apply(plan);
        Component out = repaired.components().getFirst();
        assertNull(out.params().get("window_style"));
        assertFalse(Boolean.TRUE.equals(out.params().get("suppress_windows")));
        assertFalse(Boolean.TRUE.equals(out.params().get("hollow")));
    }
}
