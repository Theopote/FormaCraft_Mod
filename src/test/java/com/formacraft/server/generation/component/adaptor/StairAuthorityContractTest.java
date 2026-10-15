package com.formacraft.server.generation.component.adaptor;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.routing.GenerationAuthority;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StairAuthorityContractTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void clearPlanContext() {
        UnifiedGeneratorRouter.clearPlanGenerationAuthority();
        UnifiedGeneratorRouter.clearPlanMeta();
    }

    @Test
    void typologyStairUsesStairExecutorWithTypologyAuthority() throws Exception {
        LlmPlan plan = loadFixture("stair_typology_authority.json");
        Component stair = plan.components().getFirst();
        Map<String, Object> paramsBefore = new HashMap<>(stair.params());

        UnifiedGeneratorRouter.setPlanGenerationAuthority(GenerationAuthority.TYPOLOGY);
        UnifiedGeneratorRouter.setPlanMeta(plan.planMeta());

        SemanticComponent semantic = new SemanticComponent("STAIR_SYSTEM", null, stair);
        RoutedGenerationResult result = UnifiedGeneratorRouter.generateResult(semantic, null);

        assertFalse(result.isHardFail(), () -> String.valueOf(result.diagnostics()));
        assertEquals(GenerationAuthority.TYPOLOGY, result.authority());
        assertEquals(ComponentExecutorId.STAIR_COMPONENT, result.executor());
        assertNotNull(result.routeReason());
        assertFalse(result.patches().isEmpty());

        assertEquals(paramsBefore.get("width"), stair.params().get("width"));
        assertEquals(paramsBefore.get("from"), stair.params().get("from"));
        assertEquals(paramsBefore.get("to"), stair.params().get("to"));
    }

    @Test
    void typologyStairRouteReasonDocumentsAuthority() {
        Component stair = stairWithExplicitEndpoints(Map.of("generation_authority", "TYPOLOGY"));
        UnifiedGeneratorRouter.setPlanGenerationAuthority(GenerationAuthority.TYPOLOGY);

        SemanticComponent semantic = new SemanticComponent("STAIR_SYSTEM", null, stair);
        RoutedGenerationResult result = UnifiedGeneratorRouter.generateResult(semantic, null);

        assertEquals(GenerationAuthority.TYPOLOGY, result.authority());
        assertEquals(ComponentExecutorId.STAIR_COMPONENT, result.executor());
        assertTrue(result.routeReason().contains("straight_stair")
                || result.routeReason().contains("stair"));
    }

    private static Component stairWithExplicitEndpoints(Map<String, Object> extraParams) {
        Map<String, Object> params = new HashMap<>(extraParams);
        params.put("width", 4);
        params.put("landing_length", 2);
        params.put("clearHeight", 3);
        params.put("from", Map.of("x", 2, "y", 0, "z", 0));
        params.put("to", Map.of("x", 2, "y", 5, "z", 8));
        params.put("material", "minecraft:stone_bricks");
        return new Component(
                "STAIR_SYSTEM",
                "stairs",
                new Vec3i(2, 0, 4),
                new Dimensions(5, 10, 6),
                List.of(),
                params);
    }

    private static LlmPlan loadFixture(String fileName) throws Exception {
        String path = "/plans/fidelity/" + fileName;
        try (var in = StairAuthorityContractTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing fixture " + path);
            String json = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            return LlmPlanParser.parseAndValidate(json);
        }
    }
}
