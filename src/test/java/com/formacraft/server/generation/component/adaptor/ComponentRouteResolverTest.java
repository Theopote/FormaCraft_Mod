package com.formacraft.server.generation.component.adaptor;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ComponentRouteResolverTest {

    @Test
    void roofUsesComponentPipelineWithoutGenerating() {
        Component roof = new Component(
                "ROOF",
                "r",
                new Vec3i(0, 8, 0),
                new Dimensions(10, 10, 3),
                List.of(),
                Map.of("roof_type", "gable"));
        var decision = ComponentRouteResolver.resolve(new SemanticComponent("ROOF", null, roof), null);
        assertEquals(ComponentExecutorId.COMPONENT_PIPELINE, decision.executor());
        assertEquals(decision, UnifiedGeneratorRouter.previewRoute(new SemanticComponent("ROOF", null, roof), null));
    }

    @Test
    void assemblyWithoutWorldFallsBackToPipelineHintOnly() {
        Component assembly = new Component(
                "ASSEMBLY",
                "a",
                new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 12),
                List.of(),
                Map.of("assembly", Map.of("preset", "spiral_watchtower")));
        var decision = ComponentRouteResolver.resolve(new SemanticComponent("ASSEMBLY", null, assembly), null);
        assertEquals(ComponentExecutorId.COMPONENT_PIPELINE, decision.executor());
    }

    @Test
    void resolveAndPreviewRouteMatch() {
        Component mass = new Component(
                "MASS_MAIN",
                "b",
                new Vec3i(0, 0, 0),
                new Dimensions(9, 9, 6),
                List.of(),
                Map.of());
        SemanticComponent semantic = new SemanticComponent("MASS_MAIN", null, mass);
        assertEquals(
                ComponentRouteResolver.resolve(semantic, null),
                UnifiedGeneratorRouter.previewRoute(semantic, null));
        assertNotEquals(ComponentExecutorId.NONE, ComponentRouteResolver.resolve(semantic, null).executor());
    }
}
