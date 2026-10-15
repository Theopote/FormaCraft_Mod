package com.formacraft.common.proportion;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DesignProvenanceGrammarTest {

    @Test
    void openingGrammarDoesNotClampAiPlannedWindowRatio() {
        Component facade = new Component(
                "FACADE_WINDOWS",
                "f",
                new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8),
                List.of(),
                Map.of("window_ratio", 0.42));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(facade))
                .proportionHints(Map.of("typology", "cottage"))
                .designSources(Map.of("window_ratio", "AI_PLANNED"))
                .build();

        Component out = OpeningGrammarResolver.apply(plan, facade);
        assertEquals(0.42, out.params().get("window_ratio"));
    }

    @Test
    void roofGrammarDoesNotOverrideAiPlannedRoofType() {
        Component roof = new Component(
                "ROOF",
                "r",
                new Vec3i(0, 8, 0),
                new Dimensions(12, 10, 4),
                List.of(),
                Map.of("roof_type", "gable", "roof_pitch_degrees", 58));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(roof))
                .proportionHints(Map.of("roof_specialty", "mansard_dormer"))
                .designSources(Map.of(
                        "roof_type", "AI_PLANNED",
                        "roof_pitch_degrees", "AI_PLANNED"))
                .build();

        Component out = RoofGrammarResolver.apply(plan, roof);
        assertEquals("gable", out.params().get("roof_type"));
        assertEquals(58, out.params().get("roof_pitch_degrees"));
        assertNotEquals("mansard", out.params().get("roof_type"));
    }

    @Test
    void crownGrammarDoesNotOverrideAiPlannedTemplate() {
        Component crown = new Component(
                "CROWN",
                "c",
                new Vec3i(0, 12, 0),
                new Dimensions(6, 6, 4),
                List.of(),
                Map.of("crown_template", "spire"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(crown))
                .proportionHints(Map.of("typology", "baroque_onion"))
                .designSources(Map.of("crown_template", "AI_PLANNED"))
                .build();

        Component out = CrownGrammarResolver.apply(plan, crown);
        assertEquals("spire", out.params().get("crown_template"));
    }
}
