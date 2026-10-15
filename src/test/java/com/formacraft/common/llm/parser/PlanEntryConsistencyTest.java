package com.formacraft.common.llm.parser;

import com.formacraft.common.generation.routing.GenerationFallbackMatrix;
import com.formacraft.common.generation.routing.GenerationAuthority;
import com.formacraft.common.generation.routing.GenerationFallbackPolicy;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.compiler.ComponentPlanCompiler;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlanEntryConsistencyTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void clearDiagnostics() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void parseAndParseAndValidateProduceSameCompileOutcomeForAuthorityContract() throws Exception {
        String json = loadFixture("authority_contract_components.json");
        LlmPlan fromParse = LlmPlanParser.parse(json);
        LlmPlan fromValidate = LlmPlanParser.parseAndValidate(json);

        var patchesParse = ComponentPlanCompiler.compile(fromParse, BlockPos.ORIGIN, null, null, false);
        AssemblyCompileDiagnostics.clear();
        var patchesValidate = ComponentPlanCompiler.compile(fromValidate, BlockPos.ORIGIN, null, null, false);

        assertEquals(patchesValidate.size(), patchesParse.size());
        assertFalse(AssemblyCompileDiagnostics.hasGap());
    }

    @Test
    void componentsNoFallbackBlocksTypologyEmptyViaParsePath() throws Exception {
        LlmPlan plan = LlmPlanParser.parse(loadFixture("components_no_fallback.json"));
        var mass = plan.components().getFirst();
        var policy = GenerationFallbackPolicy.forAuthority(GenerationAuthority.COMPONENTS);
        assertFalse(GenerationFallbackMatrix.effectiveTypologyEmpty(
                GenerationAuthority.COMPONENTS, policy, mass, plan.planMeta()));
    }

    private static String loadFixture(String fileName) throws Exception {
        String path = "/plans/fidelity/" + fileName;
        try (var in = PlanEntryConsistencyTest.class.getResourceAsStream(path)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
