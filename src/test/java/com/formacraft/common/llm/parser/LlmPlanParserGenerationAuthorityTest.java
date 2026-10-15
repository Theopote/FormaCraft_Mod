package com.formacraft.common.llm.parser;

import com.formacraft.common.llm.dto.LlmPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmPlanParserGenerationAuthorityTest {

    private static final String MINIMAL_PLAN = """
            {
              "mode": "build",
              "anchor": { "x": 0, "y": 64, "z": 0 },
              "components": [
                {
                  "component_type": "MASS_MAIN",
                  "slot_id": "body",
                  "relative_position": { "x": 0, "y": 0, "z": 0 },
                  "dimensions": { "width": 8, "depth": 8, "height": 6 },
                  "features": [],
                  "params": {}
                }
              ]
            }
            """;

    @Test
    void parseAndValidateAcceptsOmittedAuthority() {
        assertDoesNotThrow(() -> {
            LlmPlan plan = LlmPlanParser.parseAndValidate(MINIMAL_PLAN);
            assertNotNull(plan);
        });
    }

    @Test
    void parseAndValidateAcceptsValidPlanMetaAuthority() throws Exception {
        String json = MINIMAL_PLAN.replace(
                "\"mode\": \"build\"",
                "\"mode\": \"build\",\n  \"plan_meta\": { \"generation_authority\": \"TYPOLOGY\" }");
        LlmPlan plan = LlmPlanParser.parseAndValidate(json);
        assertNotNull(plan);
        assertEqualsAuthority("TYPOLOGY", plan.planMeta().get("generation_authority"));
    }

    @Test
    void parseAndValidateRejectsInvalidPlanMetaAuthority() {
        String json = MINIMAL_PLAN.replace(
                "\"mode\": \"build\"",
                "\"mode\": \"build\",\n  \"plan_meta\": { \"generation_authority\": \"TYPOLOOGY\" }");
        PlanParseException ex = assertThrows(PlanParseException.class, () -> LlmPlanParser.parseAndValidate(json));
        assertTrue(ex.getMessage().contains("plan_meta"));
        assertTrue(ex.getMessage().contains("TYPOLOOGY"));
    }

    @Test
    void parseAndValidateRejectsInvalidComponentAuthority() {
        String json = MINIMAL_PLAN.replace("\"params\": {}", "\"params\": { \"generation_authority\": \"BOGUS\" }");
        PlanParseException ex = assertThrows(PlanParseException.class, () -> LlmPlanParser.parseAndValidate(json));
        assertTrue(ex.getMessage().contains("components[0].params"));
        assertTrue(ex.getMessage().contains("BOGUS"));
    }

    private static void assertEqualsAuthority(String expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, String.valueOf(actual));
    }
}
