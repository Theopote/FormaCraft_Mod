package com.formacraft.common.llm.parser;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmPlanParserDistinguishingFeaturesTest {

    @Test
    void parseAndValidatePropagatesDistinguishingFeaturesIntoStyleAndComponents() throws PlanParseException {
        String json = """
                {
                  "mode": "build",
                  "style_profile": "Chinese_Traditional",
                  "anchor": {"x": 0, "y": 64, "z": 0},
                  "distinguishing_features": ["lattice windows", "dougong brackets"],
                  "components": [
                    {
                      "component_type": "MASS_MAIN",
                      "relative_position": {"x": 0, "y": 0, "z": 0},
                      "dimensions": {"width": 16, "depth": 12, "height": 8},
                      "features": [],
                      "params": {}
                    }
                  ]
                }
                """;

        LlmPlan plan = LlmPlanParser.parseAndValidate(json);

        assertNotNull(plan.distinguishingFeatures());
        assertEquals(2, plan.distinguishingFeatures().size());
        assertNotNull(plan.styleAttributes());
        assertTrue(plan.styleAttributes().decorativeElements().contains("lattice windows"));

        Component mass = plan.components().get(0);
        assertTrue(mass.features().contains("lattice_windows"));
        assertTrue(mass.features().contains("dougong_brackets"));
    }

    @Test
    void normalizePreservesDistinguishingFeatures() {
        LlmPlan plan = new LlmPlan(
                LlmPlan.Mode.build,
                "DEFAULT",
                new com.formacraft.common.llm.dto.Vec3i(0, 64, 0),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                java.util.List.of("shell roof"),
                null
        );

        LlmPlan normalized = LlmPlanAnchorNormalizer.normalize(plan);
        assertEquals(java.util.List.of("shell roof"), normalized.distinguishingFeatures());
    }
}
