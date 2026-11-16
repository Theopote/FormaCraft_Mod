package com.formacraft.common.proportion;

import com.formacraft.common.generation.design.DesignProvenance;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.style.StyleIntentResolver;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void putIfAllowedRespectsWindowRatioAliasLock() {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("windowRatio", 0.35);
        params.put("design_sources", Map.of("windowRatio", "USER_EXPLICIT"));
        LlmPlan plan = LlmPlanTestFixtures.builder().build();

        assertFalse(DesignProvenance.putIfAllowed(plan, params, "window_ratio", 0.25));
        assertEquals(0.35, params.get("windowRatio"));
        assertFalse(params.containsKey("window_ratio"));
    }

    @Test
    void openingGrammarRespectsComponentLevelWindowRatioLock() {
        Component facade = new Component(
                "FACADE_WINDOWS",
                "f",
                new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8),
                List.of(),
                Map.of(
                        "window_ratio", 0.42,
                        "design_sources", Map.of("window_ratio", "AI_PLANNED")));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(facade))
                .proportionHints(Map.of("typology", "cottage"))
                .build();

        Component out = OpeningGrammarResolver.apply(plan, facade);
        assertEquals(0.42, out.params().get("window_ratio"));
    }

    @Test
    void removeIfAllowedChecksEachAliasIndependently() {
        Map<String, Object> params = new HashMap<>();
        params.put("crown_template", "spire");
        params.put("crownTemplate", "spire");
        params.put("design_sources", Map.of("crownTemplate", "USER_EXPLICIT"));

        LlmPlan plan = LlmPlanTestFixtures.builder().build();
        DesignProvenance.removeIfAllowed(plan, params, "crown_template", "crownTemplate");

        assertFalse(params.containsKey("crown_template"));
        assertEquals("spire", params.get("crownTemplate"));
    }

    @Test
    void removeIfAllowedDoesNotStripLockedSiblingParam() {
        Map<String, Object> params = new HashMap<>();
        params.put("roof_specialty", "mansard_dormer");
        params.put("roof_dormers", true);
        params.put("design_sources", Map.of("roof_dormers", "AI_PLANNED"));

        LlmPlan plan = LlmPlanTestFixtures.builder().build();
        DesignProvenance.removeIfAllowed(plan, params, "roof_specialty");
        DesignProvenance.removeIfAllowed(plan, params, "roof_dormers");

        assertFalse(params.containsKey("roof_specialty"));
        assertEquals(Boolean.TRUE, params.get("roof_dormers"));
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

    @Test
    void styleIntentPreservesComponentLevelAiPlannedXieshan() {
        Map<String, Object> params = new HashMap<>();
        params.put("roof_type", "xieshan");
        params.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Component mass = new Component(
                "MASS_MAIN",
                "hall",
                new Vec3i(0, 0, 0),
                new Dimensions(24, 16, 10),
                List.of(),
                params);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("Chinese_Classical")
                .components(List.of(mass))
                .build();

        Component out = StyleIntentResolver.apply(plan, mass);
        assertEquals("xieshan", out.params().get("roof_type"));
    }

    @Test
    void styleIntentDoesNotFillRoofWhenComponentAiPlannedLockBlocks() {
        Map<String, Object> params = new HashMap<>();
        params.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Component mass = new Component(
                "MASS_MAIN",
                "hall",
                new Vec3i(0, 0, 0),
                new Dimensions(24, 16, 10),
                List.of(),
                params);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("Chinese_Classical")
                .components(List.of(mass))
                .build();

        Component out = StyleIntentResolver.apply(plan, mass);
        Object roofType = out.params() == null ? null : out.params().get("roof_type");
        assertTrue(roofType == null || String.valueOf(roofType).isBlank());
    }

    @Test
    void putIfAllowedRespectsUserExplicitRoofAcrossAlias() {
        Map<String, Object> params = new HashMap<>();
        params.put("roof_type", "xieshan");
        params.put("design_sources", Map.of("roof_type", "USER_EXPLICIT"));
        LlmPlan plan = LlmPlanTestFixtures.builder().build();

        assertFalse(DesignProvenance.putIfAllowed(plan, params, "roofType", "xuanshan"));
        assertEquals("xieshan", params.get("roof_type"));
    }

    @Test
    void styleIntentPreservesUserExplicitCourtyardPlanType() {
        Map<String, Object> params = new HashMap<>();
        params.put("plan_type", "courtyard");
        params.put("roof_type", "xieshan");
        params.put("design_sources", Map.of(
                "plan_type", "USER_EXPLICIT",
                "roof_type", "AI_PLANNED"));
        Component mass = new Component(
                "MASS_MAIN",
                "house",
                new Vec3i(0, 0, 0),
                new Dimensions(28, 22, 8),
                List.of(),
                params);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("Chinese_Classical")
                .components(List.of(mass))
                .build();

        Component out = StyleIntentResolver.apply(plan, mass);
        assertEquals("courtyard", out.params().get("plan_type"));
        assertEquals("xieshan", out.params().get("roof_type"));
    }
}
