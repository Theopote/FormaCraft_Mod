package com.formacraft.server.generation.component.adaptor;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.genome.BuildingGenome;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.server.generation.GenerationCompileContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedGeneratorRouterTypologyExclusiveTest {
    @Test
    void detailQueriesCannotReplaceBuildingEnvelopes() {
        for (String type : List.of("MASS_MAIN", "MASS_SECONDARY", "ROOF", "FOUNDATION"))
            assertTrue(UnifiedGeneratorRouter.preservesBuildingEnvelope(type));
        assertTrue(UnifiedGeneratorRouter.preservesBuildingEnvelope("ENTRANCE"));
        assertTrue(UnifiedGeneratorRouter.preservesBuildingEnvelope("FACADE_WINDOWS"));
        assertFalse(UnifiedGeneratorRouter.preservesBuildingEnvelope("DECOR_DETAIL"));
    }

    @Test
    void typologyExclusivePlanBlocksArchetypeConfidenceFallback() {
        BuildingGenome genome = new BuildingGenome();
        genome.archetype.confidence = 0.9;

        Component facade = new Component(
                "FACADE_WINDOWS",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 6),
                List.of(),
                Map.of()
        );
        SemanticComponent semantic = new SemanticComponent("FACADE_WINDOWS", null, facade, null, null, genome);

        GenerationCompileContext.runWith(new GenerationCompileContext(null, Map.of(), true),
                () -> assertFalse(shouldUseStructureFallback(semantic)));
    }

    @Test
    void typologyExclusivePlanAllowsExplicitStructureRouting() {
        BuildingGenome genome = new BuildingGenome();
        genome.archetype.confidence = 0.9;

        Component structure = new Component(
                "STRUCTURE",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(9, 180, 44),
                List.of("typology:suspension_bridge"),
                Map.of("typology_id", "suspension_bridge")
        );
        SemanticComponent semantic = new SemanticComponent("STRUCTURE", null, structure, null, null, genome);

        GenerationCompileContext.runWith(new GenerationCompileContext(null, Map.of(), true),
                () -> assertTrue(shouldUseStructureFallback(semantic)));
    }

    @Test
    void archetypeConfidenceStillTriggersFallbackWithoutTypologyExclusivePlan() {
        BuildingGenome genome = new BuildingGenome();
        genome.archetype.confidence = 0.9;

        Component facade = new Component(
                "FACADE_WINDOWS",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 6),
                List.of(),
                Map.of()
        );
        SemanticComponent semantic = new SemanticComponent("FACADE_WINDOWS", null, facade, null, null, genome);

        assertTrue(shouldUseStructureFallback(semantic));
    }

    private static boolean shouldUseStructureFallback(SemanticComponent semantic) {
        return UnifiedGeneratorRouter.shouldUseStructureFallbackForRouting(semantic);
    }
}
