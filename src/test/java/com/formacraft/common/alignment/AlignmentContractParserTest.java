package com.formacraft.common.alignment;

import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AlignmentContractParserTest {

    @Test
    void resolvesTopLevelField() {
        BayRhythm rhythmX = new BayRhythm(
                List.of(new BaySpec(4, "wing"), new BaySpec(6, "center"), new BaySpec(4, "wing")),
                null,
                null
        );
        AlignmentAndSymmetry contract = new AlignmentAndSymmetry("bilateral_x", 0, null, rhythmX, null);
        LlmPlan plan = LlmPlanTestFixtures.withAlignment(contract, List.of());

        AlignmentAndSymmetry resolved = AlignmentContractParser.resolve(plan);
        assertNotNull(resolved);
        assertEquals("bilateral_x", resolved.symmetryType());
        assertEquals(3, resolved.rhythmX().sideBays().size());
    }

    @Test
    void resolvesFromProportionHints() {
        Map<String, Object> hints = Map.of(
                "alignment_and_symmetry", Map.of(
                        "symmetry_type", "bilateral_z",
                        "center_axis_z", 12,
                        "rhythm_z", Map.of(
                                "side_bays", List.of(
                                        Map.of("width", 3, "role", "service"),
                                        Map.of("width", 5, "role", "hall"),
                                        Map.of("width", 3, "role", "service")
                                )
                        )
                )
        );
        LlmPlan plan = LlmPlanTestFixtures.minimal(hints, List.of());

        AlignmentAndSymmetry resolved = AlignmentContractParser.resolve(plan);
        assertNotNull(resolved);
        assertEquals("bilateral_z", resolved.symmetryType());
        assertEquals(12, resolved.centerAxisZ());
        assertEquals(11, resolved.rhythmZ().totalSpan());
    }

    @Test
    void infersFromGlobalConstraintsMirrorX() {
        LlmPlan plan = new LlmPlan(
                LlmPlan.Mode.build,
                "DEFAULT",
                null,
                new GlobalConstraints(GlobalConstraints.Facing.SOUTH, GlobalConstraints.Symmetry.MIRROR_X, null),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        AlignmentAndSymmetry resolved = AlignmentContractParser.resolve(plan);
        assertNotNull(resolved);
        assertEquals("bilateral_x", resolved.symmetryType());
    }

    @Test
    void returnsNullWhenNoContract() {
        assertNull(AlignmentContractParser.resolve(null));
        assertNull(AlignmentContractParser.resolve(LlmPlanTestFixtures.minimal(null, List.of())));
    }
}
