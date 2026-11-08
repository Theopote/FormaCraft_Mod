package com.formacraft.common.llm;

import com.formacraft.common.llm.dto.LlmPlanTestFixtures;

import com.formacraft.common.generation.component.util.ComponentCrownDecorator;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NonClassicalEnrichmentGuardTest {

    @Test
    void isActiveWhenEnrichmentGuardPresent() {
        LlmPlan plan = planWithGuard("non_classical_marker:zaha");
        assertTrue(NonClassicalEnrichmentGuard.isActive(plan));
        assertTrue(NonClassicalEnrichmentGuard.blocksCrownInference(plan));
    }

    @Test
    void inactiveWithoutEnrichmentGuard() {
        LlmPlan plan = planWithGuard(null);
        assertFalse(NonClassicalEnrichmentGuard.isActive(plan));
    }

    @Test
    void eastAsianIntentActivatesDespiteClassicalPollution() {
        String prompt = "生成一座重檐歇山顶大雄宝殿，前有月台，左右各一配殿，配殿用悬山顶，主殿必须是重檐歇山。";
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("DEFAULT")
                .anchor(new Vec3i(0, 64, 0))
                .components(List.of(
                        new Component(
                                "MASS_MAIN",
                                "body",
                                new Vec3i(0, 0, 0),
                                new Dimensions(32, 40, 19),
                                List.of("pilasters", "cornice"),
                                Map.of("component_id", "component_1", "facade_profile", "vertical_pilasters")
                        ),
                        new Component(
                                "CROWN",
                                "body",
                                new Vec3i(10, 24, 14),
                                new Dimensions(13, 13, 10),
                                List.of("crown"),
                                Map.of("component_id", "component_8", "crown_template", "CLASSICAL_CUPOLA")
                        ),
                        new Component(
                                "ROOF",
                                "body",
                                new Vec3i(-2, 19, -2),
                                new Dimensions(36, 44, 5),
                                List.of("overhang"),
                                Map.of("component_id", "component_2", "host_id", "component_1", "roof_type", "hip")
                        )
                ))
                .proportionHints(Map.of("typology", "classical_monument", "crown_assembly", true))
                .planMeta(Map.of(
                        "request_intent", Map.of("original_text", prompt),
                        "request_intent_text", prompt))
                .build();

        assertTrue(NonClassicalEnrichmentGuard.isActive(plan));
        LlmPlan sanitized = NonClassicalEnrichmentGuard.sanitize(plan);
        assertTrue(sanitized.components().stream().noneMatch(c -> "CROWN".equalsIgnoreCase(c.componentType())));
        Component roof = sanitized.components().stream()
                .filter(c -> "ROOF".equalsIgnoreCase(c.componentType()))
                .findFirst()
                .orElseThrow();
        assertEquals("hip", String.valueOf(roof.params().get("roof_type")));
        Component mass = sanitized.components().stream()
                .filter(c -> "MASS_MAIN".equalsIgnoreCase(c.componentType()))
                .findFirst()
                .orElseThrow();
        assertEquals("none", String.valueOf(mass.params().get("facade_profile")));
    }

    @Test
    void activeFromPlanHeuristicWhenGuardMissing() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("DEFAULT")
                .anchor(new Vec3i(0, 64, 0))
                .components(List.of(
                        new Component(
                                "MASS_MAIN",
                                null,
                                new Vec3i(0, 2, 0),
                                new Dimensions(8, 8, 6),
                                List.of("tower_body_lower", "observation_sphere"),
                                Map.of("facade_profile", "vertical_pilasters")
                        )
                ))
                .distinguishingFeatures(List.of("television tower"))
                .build();
        assertTrue(NonClassicalEnrichmentGuard.isActive(plan));
    }

    @Test
    void inactiveForClassicalPantheonModulePlan() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("DEFAULT")
                .anchor(new Vec3i(0, 64, 0))
                .components(List.of(
                        new Component(
                                "MODULE",
                                null,
                                new Vec3i(0, 0, 0),
                                new Dimensions(50, 50, 45),
                                List.of("landmark:pantheon"),
                                Map.of("module_id", "pantheon")
                        )
                ))
                .distinguishingFeatures(List.of("oculus", "portico", "coffered_dome"))
                .build();
        assertFalse(NonClassicalEnrichmentGuard.isActive(plan));
    }

    @Test
    void sanitizeStripsPilastersAndCrownComponents() {
        Map<String, Object> massParams = new HashMap<>();
        massParams.put("facade_profile", "vertical_pilasters");
        massParams.put("roof_type", "mansard");

        Map<String, Object> hints = new HashMap<>();
        hints.put("typology", "stadium_bowl");
        hints.put("crown_assembly", true);
        hints.put("roof_specialty", "mansard_dormer");

        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("Modern_Stadium_Elliptical")
                .anchor(new Vec3i(0, 64, 0))
                .globalConstraints(new GlobalConstraints(GlobalConstraints.Facing.NORTH, GlobalConstraints.Symmetry.NONE, null))
                .components(List.of(
                        new Component(
                                "MASS_MAIN",
                                null,
                                new Vec3i(0, 2, 0),
                                new Dimensions(28, 20, 12),
                                List.of("pilasters", "curvilinear form"),
                                massParams
                        ),
                        new Component(
                                "ROOF",
                                null,
                                new Vec3i(0, 14, 0),
                                new Dimensions(30, 22, 6),
                                List.of("sail-like shell roof"),
                                Map.of(
                                        "roof_type", "dome",
                                        "generation_method", "revolved_surface_around_axis",
                                        "crown_template", "SIMPLE_DOME"
                                )
                        ),
                        new Component(
                                "CROWN",
                                null,
                                new Vec3i(0, 20, 0),
                                new Dimensions(8, 8, 6),
                                List.of("crown"),
                                Map.of("crown_template", "CLASSICAL_CUPOLA")
                        )
                ))
                .proportionHints(hints)
                .distinguishingFeatures(List.of("curvilinear form"))
                .enrichmentGuard("non_classical_marker:zaha")
                .build();

        LlmPlan sanitized = NonClassicalEnrichmentGuard.sanitize(plan);

        Component mass = sanitized.components().get(0);
        assertEquals("none", mass.params().get("facade_profile"));
        assertEquals("flat", mass.params().get("roof_type"));
        assertFalse(mass.features().contains("pilasters"));

        Component roof = sanitized.components().get(1);
        assertEquals("dome", roof.params().get("roof_type"));
        assertEquals("revolved_surface_around_axis", roof.params().get("generation_method"));

        assertEquals(2, sanitized.components().size());
        assertNull(sanitized.proportionHints().get("typology"));
        assertEquals(false, sanitized.proportionHints().get("crown_assembly"));
        assertFalse(ComponentCrownDecorator.shouldApply(sanitized, mass.params()));
    }

    @Test
    void sanitizeDoesNotOverwriteAiPlannedRoofType() {
        Map<String, Object> massParams = new HashMap<>();
        massParams.put("facade_profile", "vertical_pilasters");
        massParams.put("roof_type", "mansard");
        massParams.put("design_sources", Map.of("roof_type", "AI_PLANNED", "facade_profile", "USER_EXPLICIT"));

        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("Modern_Expressionist")
                .anchor(new Vec3i(0, 64, 0))
                .components(List.of(
                        new Component(
                                "MASS_MAIN",
                                null,
                                new Vec3i(0, 2, 0),
                                new Dimensions(20, 16, 10),
                                List.of("pilasters"),
                                massParams
                        ),
                        new Component(
                                "CROWN",
                                null,
                                new Vec3i(0, 12, 0),
                                new Dimensions(6, 6, 4),
                                List.of("crown"),
                                Map.of(
                                        "crown_template", "CLASSICAL_CUPOLA",
                                        "design_sources", Map.of("crown_template", "AI_PLANNED"))
                        )
                ))
                .enrichmentGuard("non_classical_marker:zaha")
                .build();

        LlmPlan sanitized = NonClassicalEnrichmentGuard.sanitize(plan);
        Component mass = sanitized.components().stream()
                .filter(c -> "MASS_MAIN".equals(c.componentType()))
                .findFirst()
                .orElseThrow();
        assertEquals("mansard", mass.params().get("roof_type"));
        assertEquals("vertical_pilasters", mass.params().get("facade_profile"));
        assertTrue(sanitized.components().stream().anyMatch(c -> "CROWN".equals(c.componentType())));
    }

    private static LlmPlan planWithGuard(String guard) {
        return LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("DEFAULT")
                .anchor(new Vec3i(0, 64, 0))
                .components(List.of())
                .enrichmentGuard(guard)
                .build();
    }
}
