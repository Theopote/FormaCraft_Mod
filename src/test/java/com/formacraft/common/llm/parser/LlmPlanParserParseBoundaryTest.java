package com.formacraft.common.llm.parser;

import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.compiler.ComponentPlanCompiler;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmPlanParserParseBoundaryTest {

    @AfterEach
    void clearDiagnostics() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void parseRejectsInvalidGenerationAuthority() throws Exception {
        String json = loadFixture("invalid_generation_authority.json");
        PlanParseException ex = assertThrows(PlanParseException.class, () -> LlmPlanParser.parse(json));
        assertTrue(ex.getMessage().contains("plan_meta"));
        assertTrue(ex.getMessage().contains("TYPOLOOGY"));
    }

    @Test
    void parseAndValidateMatchesParseForInvalidAuthority() throws Exception {
        String json = loadFixture("invalid_generation_authority.json");
        assertThrows(PlanParseException.class, () -> LlmPlanParser.parseAndValidate(json));
        assertThrows(PlanParseException.class, () -> LlmPlanParser.parse(json));
    }

    @Test
    void compileRejectsInvalidAuthorityWhenPlanBypassesParse() {
        var plan = LlmPlanTestFixtures.builder()
                .mode(com.formacraft.common.llm.dto.LlmPlan.Mode.build)
                .anchor(new Vec3i(0, 64, 0))
                .planMeta(java.util.Map.of("generation_authority", "BOGUS"))
                .build();
        var patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_INVALID_GENERATION_AUTHORITY", AssemblyCompileDiagnostics.get().code());
    }

    private static String loadFixture(String fileName) throws Exception {
        String path = "/plans/fidelity/" + fileName;
        try (var in = LlmPlanParserParseBoundaryTest.class.getResourceAsStream(path)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
