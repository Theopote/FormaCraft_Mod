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
