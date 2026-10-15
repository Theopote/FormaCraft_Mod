package com.formacraft.server.generation.component.adaptor;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentPipelineRouteResolverTest {

    @Test void typologyStructureWithWindowRequestCannotBecomeOnlyAWindow() {
        var c = new Component("STRUCTURE", "main", new Vec3i(0,0,0), new Dimensions(24,20,18),
                List.of("typology:radial_fortress", "component_request:{}"), Map.of());
        var route = ComponentPipelineRouteResolver.resolve(new SemanticComponent("STRUCTURE", null, c), null);
        org.junit.jupiter.api.Assertions.assertFalse(route.explicitLibraryFirst());
        org.junit.jupiter.api.Assertions.assertFalse(route.isStepEligible(ComponentPipelineStepId.LIBRARY_EXPANDER));
        assertEquals(com.formacraft.common.generation.routing.GenerationAuthority.TYPOLOGY,
                ComponentGenerationPipeline.resolveAuthority(c));
    }

    @Test
    void roofPipelineIncludesRegistryAndTypologyWhenEligible() {
        Component roof = new Component(
                "ROOF",
                "r",
                new Vec3i(0, 8, 0),
                new Dimensions(10, 10, 3),
                List.of("typology:gothic"),
                Map.of("typology_id", "gothic_cathedral_hall"));
        var plan = ComponentPipelineRouteResolver.resolve(new SemanticComponent("ROOF", null, roof), null);
        assertTrue(plan.isStepEligible(ComponentPipelineStepId.COMPONENT_REGISTRY));
        // Typology step is present but not eligible without a world (hint-only, no geometry).
        assertTrue(plan.steps().stream().anyMatch(s -> s.id() == ComponentPipelineStepId.TYPOLOGY));
        assertTrue(plan.steps().stream()
                .filter(s -> s.id() == ComponentPipelineStepId.TYPOLOGY)
                .noneMatch(ComponentPipelineStep::eligible));
    }

    @Test
    void resolveEmbedsPipelinePlanInDecision() {
        Component mass = new Component(
                "MASS_MAIN",
                "b",
                new Vec3i(0, 0, 0),
                new Dimensions(9, 9, 6),
                List.of(),
                Map.of());
        var decision = ComponentRouteResolver.resolve(new SemanticComponent("MASS_MAIN", null, mass), null);
        assertEquals(ComponentExecutorId.COMPONENT_PIPELINE, decision.executor());
        assertTrue(decision.pipelinePlan().isStepEligible(ComponentPipelineStepId.COMPONENT_REGISTRY));
    }
}
