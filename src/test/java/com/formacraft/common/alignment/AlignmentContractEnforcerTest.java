package com.formacraft.common.alignment;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlignmentContractEnforcerTest {

    @Test
    void forcesMassDepthFromRhythmZ() {
        BayRhythm rhythmZ = new BayRhythm(
                List.of(new BaySpec(3, "service"), new BaySpec(5, "hall"), new BaySpec(3, "service")),
                null,
                null
        );
        AlignmentAndSymmetry contract = new AlignmentAndSymmetry("bilateral_z", null, 0, null, rhythmZ);
        Component mass = new Component(
                "MASS_MAIN",
                "s0",
                new Vec3i(4, 0, 6),
                new Dimensions(15, 8, 20),
                List.of(),
                Map.of("anchor_mode", "center")
        );
        LlmPlan plan = LlmPlanTestFixtures.withAlignment(contract, List.of(mass));

        List<Component> out = AlignmentContractEnforcer.apply(plan, List.of(mass));
        Component aligned = out.getFirst();

        assertEquals(15, aligned.dimensions().width());
        assertEquals(11, aligned.dimensions().depth());
        assertEquals(0, aligned.relativePosition().z());
        assertTrue(Boolean.TRUE.equals(aligned.params().get("alignment_contract_applied")));
        assertEquals(11, ((Map<?, ?>) aligned.params().get("bay_grid_z")).get("total_span"));
    }

    @Test
    void leavesNonMassComponentsUntouched() {
        AlignmentAndSymmetry contract = new AlignmentAndSymmetry(
                "bilateral_x",
                0,
                null,
                new BayRhythm(null, 3, 4),
                null
        );
        Component roof = new Component(
                "ROOF",
                "s0",
                new Vec3i(0, 8, 0),
                new Dimensions(12, 3, 12),
                List.of(),
                Map.of()
        );
        LlmPlan plan = LlmPlanTestFixtures.withAlignment(contract, List.of(roof));

        List<Component> out = AlignmentContractEnforcer.apply(plan, List.of(roof));
        assertEquals(roof, out.getFirst());
    }
}
