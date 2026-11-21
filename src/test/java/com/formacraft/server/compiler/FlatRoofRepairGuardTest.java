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

    @Test void preflightRequiresAnIdentifiableRoofWithAnUnlockedField() {
        var original = plan(Map.of());
        assertTrue(FlatRoofRepairGuard.canRequest(original, gap));
        assertTrue(FlatRoofRepairGuard.canRequest(plan(Map.of("dimensions", "USER_EXPLICIT")), gap));
        assertFalse(FlatRoofRepairGuard.canRequest(plan(Map.of(
                "dimensions", "USER_EXPLICIT", "relative_position", "USER_EXPLICIT")), gap));
        assertFalse(FlatRoofRepairGuard.canRequest(original, new CapabilityGap(gap.code(), "", "", List.of(),
                Map.of("host_id", "hall", "host_part_id", "hall", "roof_sources", List.of("ROOF@upper")))));
        var duplicate = PlanComponentRewrite.withComponents(original, List.of(
                original.components().getFirst(), original.components().getFirst()));
        assertFalse(FlatRoofRepairGuard.canRequest(duplicate, gap));
    }

    @Test void onlyAnEditThatCoversTheHostClearsTheEmittedGeometryGap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        var body = new Component("MASS_MAIN", "upper", new Vec3i(0, 0, 0), new Dimensions(9, 9, 8),
                List.of(), Map.of("component_id", "hall", "anchor_mode", "min_corner"));
        var original = plan(Map.of());
        var actualGap = emittedCoverageGap(body, original.components().getFirst()).orElseThrow();
        assertEquals(18, actualGap.details().get("uncovered_count"));
        for (int width : List.of(8, 9)) {
            var candidate = FlatRoofRepairGuard.candidate(original, actualGap,
                    new FlatRoofRepairGuard.Proposal("roof-a", "dimensions.width", width)).orElseThrow();
            var remaining = emittedCoverageGap(body, candidate.components().getFirst());
            if (width == 8) assertEquals(9, remaining.orElseThrow().details().get("uncovered_count"));
            else assertTrue(remaining.isEmpty());
        }
        assertEquals(7, original.components().getFirst().dimensions().width());
    }

    private java.util.Optional<CapabilityGap> emittedCoverageGap(Component body, Component roof) {
        var roofSemantic = new com.formacraft.common.compiler.semantic.SemanticComponent("ROOF", null, roof);
        var footprint = FlatRoofCoverageValidator.resolve(roofSemantic, net.minecraft.util.math.BlockPos.ORIGIN).orElseThrow();
        var patches = new com.formacraft.common.generation.component.impl.RoofGenerator().generate(roofSemantic);
        var host = new com.formacraft.common.compiler.semantic.SemanticComponent("MASS_MAIN", null, body);
        return FlatRoofCoverageValidator.checkHost(host, List.of(footprint), net.minecraft.util.math.BlockPos.ORIGIN,
                patches, java.util.Set.of()).map(missing -> FlatRoofCoverageValidator.hostCoverageGap("hall", missing));
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
