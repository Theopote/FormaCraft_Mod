package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FlatRoofRepairGuardTest {
    private final CapabilityGap gap = new CapabilityGap("E_FLAT_ROOF_HOST_COVERAGE", "", "components[]",
            List.of(), Map.of("host_id", "hall", "host_part_id", "hall", "roof_sources", List.of("roof-a")));

    private LlmPlan plan(Map<String, Object> provenance) {
        var roof = new Component("ROOF", "upper", new Vec3i(0, 7, 0), new Dimensions(7, 9, 1), List.of(),
                Map.of("component_id", "roof-a", "host_id", "hall", "roof_type", "flat", "design_sources", provenance));
        return LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).components(List.of(roof)).build();
    }

    @Test void candidateChangesOnlyTheProposedRoofField() {
        var original = plan(Map.of());
        var candidate = FlatRoofRepairGuard.candidate(original, gap,
                new FlatRoofRepairGuard.Proposal("roof-a", "dimensions.width", 9)).orElseThrow();
        assertEquals(new Dimensions(9, 9, 1), candidate.components().getFirst().dimensions());
        assertEquals(original.components().getFirst().params(), candidate.components().getFirst().params());
        assertEquals("upper", candidate.components().getFirst().slotId());
        assertEquals(7, original.components().getFirst().dimensions().width());
    }

    @Test void rejectsExplicitConstraintsForeignTargetsAndVerticalChanges() {
        assertTrue(FlatRoofRepairGuard.candidate(plan(Map.of("dimensions", "USER_EXPLICIT")), gap,
                new FlatRoofRepairGuard.Proposal("roof-a", "dimensions.width", 9)).isEmpty());
        for (var edit : List.of(new FlatRoofRepairGuard.Proposal("other", "dimensions.width", 9),
                new FlatRoofRepairGuard.Proposal("roof-a", "dimensions.width", Integer.MAX_VALUE),
                new FlatRoofRepairGuard.Proposal("roof-a", "relative_position.x", Integer.MIN_VALUE),
                new FlatRoofRepairGuard.Proposal("roof-a", "relative_position.y", 9))) {
            assertTrue(FlatRoofRepairGuard.candidate(plan(Map.of()), gap, edit).isEmpty());
        }
    }
}
