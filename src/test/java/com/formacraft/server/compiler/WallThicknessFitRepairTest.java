package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unfit wall_thickness on thin envelopes must solidify, not leave hollow shells.
 */
class WallThicknessFitRepairTest {

    @Test
    void solidifiesWhenWallThicknessFillsTwoSpanEnvelope() {
        Component pinnacle = new Component(
                "MASS_MAIN",
                "tower",
                new Vec3i(0, 20, 0),
                new Dimensions(2, 2, 8),
                List.of(),
                Map.of("component_id", "pinnacle_sw", "hollow", true, "wall_thickness", 1, "void_ratio", 0.15)
        );
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .components(List.of(pinnacle))
                .build();

        LlmPlan repaired = WallThicknessFitRepair.apply(plan);
        Component mass = repaired.components().getFirst();
        assertNull(mass.params().get("wall_thickness"));
        assertEquals(false, mass.params().get("hollow"));
        assertEquals(0, ((Number) mass.params().get("void_ratio")).intValue());
    }
}
