package com.formacraft.server.generation.component.adaptor;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.genome.BuildingGenome;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedGeneratorRouterTypologyExclusiveTest {

    @AfterEach
    void clearFlag() {
        UnifiedGeneratorRouter.clearTypologyExclusivePlan();
    }

    @Test
    void typologyExclusivePlanBlocksArchetypeConfidenceFallback() throws Exception {
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

        UnifiedGeneratorRouter.setTypologyExclusivePlan(true);
        assertFalse(shouldUseStructureFallback(semantic));
    }

    @Test
    void typologyExclusivePlanAllowsExplicitStructureRouting() throws Exception {
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

        UnifiedGeneratorRouter.setTypologyExclusivePlan(true);
        assertTrue(shouldUseStructureFallback(semantic));
    }

    @Test
    void archetypeConfidenceStillTriggersFallbackWithoutTypologyExclusivePlan() throws Exception {
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

    private static boolean shouldUseStructureFallback(SemanticComponent semantic) throws Exception {
        Method m = UnifiedGeneratorRouter.class.getDeclaredMethod("shouldUseStructureFallback", SemanticComponent.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, semantic);
    }
}
