package com.formacraft.server.generation.component.adaptor;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.routing.GenerationAuthority;
import com.formacraft.common.generation.routing.GenerationFallbackMatrix;
import com.formacraft.common.generation.routing.GenerationFallbackPolicy;
import com.formacraft.common.generation.routing.PlanMetaAccess;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.llm.parser.PlanParseException;
import com.formacraft.common.typology.TypologyComponentRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorityRoutingContractTest {

    @AfterEach
    void clearPlanContext() {
        UnifiedGeneratorRouter.clearPlanGenerationAuthority();
        UnifiedGeneratorRouter.clearPlanMeta();
    }

    @Test
    void componentsNoFallbackBlocksTypologyEmptyWithoutFlag() throws Exception {
        LlmPlan plan = loadFixture("components_no_fallback.json");
        Component mass = plan.components().getFirst();
        GenerationFallbackPolicy policy = GenerationFallbackPolicy.forAuthority(GenerationAuthority.COMPONENTS);

        assertTrue(TypologyComponentRouter.hasTypologyHint(mass));
        assertFalse(GenerationFallbackMatrix.effectiveTypologyEmpty(
                GenerationAuthority.COMPONENTS, policy, mass, plan.planMeta()));
        assertFalse(GenerationFallbackMatrix.effectiveStructureEmpty(
                GenerationAuthority.COMPONENTS, policy, mass, plan.planMeta()));
    }

    @Test
    void componentsTypologyEmptyFallbackFlagEnablesMatrix() throws Exception {
        LlmPlan plan = loadFixture("components_typology_empty_fallback.json");
        Component mass = plan.components().getFirst();
        GenerationFallbackPolicy policy = GenerationFallbackPolicy.forAuthority(GenerationAuthority.COMPONENTS);

        assertTrue(PlanMetaAccess.allowTypologyEmptyFallback(null, plan.planMeta()));
        assertTrue(GenerationFallbackMatrix.effectiveTypologyEmpty(
                GenerationAuthority.COMPONENTS, policy, mass, plan.planMeta()));
    }

    @Test
    void componentsStructureEmptyFallbackFlagEnablesMatrix() throws Exception {
        LlmPlan plan = loadFixture("components_structure_empty_fallback.json");
        Component mass = plan.components().getFirst();
        GenerationFallbackPolicy policy = GenerationFallbackPolicy.forAuthority(GenerationAuthority.COMPONENTS);

        assertTrue(PlanMetaAccess.allowStructureEmptyFallback(null, plan.planMeta()));
        assertTrue(GenerationFallbackMatrix.effectiveStructureEmpty(
                GenerationAuthority.COMPONENTS, policy, mass, plan.planMeta()));
    }

    @Test
    void capturedLibraryMissFailsRouting() throws Exception {
        LlmPlan plan = loadFixture("captured_library_miss.json");
        Component mass = plan.components().getFirst();

        UnifiedGeneratorRouter.setPlanGenerationAuthority(GenerationAuthority.CAPTURED_LIBRARY);
        UnifiedGeneratorRouter.setPlanMeta(plan.planMeta());

        SemanticComponent semantic = new SemanticComponent("MASS_MAIN", null, mass);
        RoutedGenerationResult result = UnifiedGeneratorRouter.generateResult(semantic, null);

        assertTrue(result.isHardFail());
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> "captured_library_miss".equals(d.code())));
    }

    @Test
    void invalidGenerationAuthorityRejectedAtParse() {
        assertThrows(PlanParseException.class, () -> loadFixture("invalid_generation_authority.json"));
    }

    private static LlmPlan loadFixture(String fileName) throws Exception {
        String path = "/plans/fidelity/" + fileName;
        try (var in = AuthorityRoutingContractTest.class.getResourceAsStream(path)) {
            String json = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            return LlmPlanParser.parseAndValidate(json);
        }
    }
}
