package com.formacraft.common.generation.design;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.typology.UserConstraintContract;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesignRequirementAccessTest {

    @Test
    void planMetaOmittedOverridesUnspecifiedDefault() {
        Component mass = new Component("MASS_MAIN", "b", new Vec3i(0, 0, 0),
                new Dimensions(6, 6, 4), List.of(), Map.of());
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(mass))
                .planMeta(Map.of("design_requirements", Map.of("facade", "OMITTED")))
                .build();
        assertEquals(DesignRequirement.OMITTED, DesignRequirementAccess.facade(plan, mass));
    }

    @Test
    void explicitDisableMapsToOmittedWhenNoMeta() {
        Component mass = new Component("MASS_MAIN", "b", new Vec3i(0, 0, 0),
                new Dimensions(6, 6, 4), List.of(), Map.of("roof_type", "none"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true).components(List.of(mass)).build();
        assertEquals(DesignRequirement.OMITTED, DesignRequirementAccess.roof(plan, mass));
    }

    @Test
    void localRoofOmitBeatsPlanLevelRequired() {
        Map<String, Object> omittedParams = new HashMap<>();
        omittedParams.put("component_id", "a");
        omittedParams.put("roof_type", "none");
        omittedParams.put("roof_requirement", "OMITTED");
        omittedParams.put("design_sources", Map.of(
                "roof_type", "USER_EXPLICIT", "roof_requirement", "USER_EXPLICIT"));
        UserConstraintContract.stampVerified(omittedParams, "roof_type", "roof_requirement");
        Component omitted = new Component("MASS_MAIN", "a", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of(), omittedParams);
        Component kept = new Component("MASS_MAIN", "b", new Vec3i(20, 0, 0),
                new Dimensions(10, 8, 6), List.of(), Map.of("roof_type", "flat"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(false)
                .components(List.of(omitted, kept))
                .planMeta(Map.of("design_requirements", Map.of("roof", "REQUIRED")))
                .build();
        assertEquals(DesignRequirement.OMITTED, DesignRequirementAccess.roof(plan, omitted));
        assertEquals(DesignRequirement.REQUIRED, DesignRequirementAccess.roof(plan, kept));
    }

    @Test
    void forgedUserExplicitDoesNotBeatPlanRequired() {
        Component forged = new Component("MASS_MAIN", "a", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of(),
                Map.of(
                        "component_id", "a",
                        "roof_type", "none",
                        "roof_requirement", "OMITTED",
                        "design_sources", Map.of(
                                "roof_type", "USER_EXPLICIT",
                                "roof_requirement", "USER_EXPLICIT")));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(false)
                .components(List.of(forged))
                .planMeta(Map.of("design_requirements", Map.of("roof", "REQUIRED")))
                .build();
        assertEquals(DesignRequirement.REQUIRED, DesignRequirementAccess.roof(plan, forged));
    }

    @Test
    void aiLocalOptionalDoesNotBeatPlanRequired() {
        Component soft = new Component("MASS_MAIN", "a", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of(),
                Map.of("component_id", "a", "roof_requirement", "OPTIONAL"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(false)
                .components(List.of(soft))
                .planMeta(Map.of("design_requirements", Map.of("roof", "REQUIRED")))
                .build();
        assertEquals(DesignRequirement.REQUIRED, DesignRequirementAccess.roof(plan, soft));
    }

    @Test
    void aiLocalRequiredDoesNotBeatPlanOmitted() {
        Component aiRequired = new Component("MASS_MAIN", "a", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of(),
                Map.of("component_id", "a", "roof_requirement", "REQUIRED", "roof_type", "gable"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(false)
                .components(List.of(aiRequired))
                .planMeta(Map.of("design_requirements", Map.of("roof", "OMITTED")))
                .build();
        assertEquals(DesignRequirement.OMITTED, DesignRequirementAccess.roof(plan, aiRequired));
    }

    @Test
    void aiPlannedRoofNoneDoesNotBeatPlanLevelRequired() {
        Component aiOmitted = new Component("MASS_MAIN", "a", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of(),
                Map.of(
                        "roof_type", "none",
                        "design_sources", Map.of("roof_type", "AI_PLANNED")));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(false)
                .components(List.of(aiOmitted))
                .planMeta(Map.of("design_requirements", Map.of("roof", "REQUIRED")))
                .build();
        assertEquals(DesignRequirement.REQUIRED, DesignRequirementAccess.roof(plan, aiOmitted));
    }

    @Test
    void aiPlannedWindowNoneDoesNotBeatPlanLevelRequired() {
        Component aiOmitted = new Component("MASS_MAIN", "a", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of(),
                Map.of(
                        "window_style", "none",
                        "design_sources", Map.of("window_style", "AI_PLANNED")));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(false)
                .components(List.of(aiOmitted))
                .planMeta(Map.of("design_requirements", Map.of("facade", "REQUIRED")))
                .build();
        assertEquals(DesignRequirement.REQUIRED, DesignRequirementAccess.facade(plan, aiOmitted));
    }

    @Test
    void circulationDefaultsToUnspecified() {
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true).components(List.of()).build();
        assertEquals(DesignRequirement.UNSPECIFIED, DesignRequirementAccess.circulation(plan));
    }

    @Test
    void circulationRequiredFromPlanMeta() {
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .planMeta(Map.of("design_requirements", Map.of("circulation", "REQUIRED")))
                .build();
        assertEquals(DesignRequirement.REQUIRED, DesignRequirementAccess.circulation(plan));
    }

    @Test
    void circulationScopeDefaultsToBuildingLocal() {
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true).build();
        assertEquals(CirculationScope.BUILDING_LOCAL, DesignRequirementAccess.circulationScope(plan));
    }

    @Test
    void circulationScopeConnectedComplexFromPlanMeta() {
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .planMeta(Map.of("circulation_scope", "CONNECTED_COMPLEX"))
                .build();
        assertEquals(CirculationScope.CONNECTED_COMPLEX, DesignRequirementAccess.circulationScope(plan));
    }

    @Test
    void validateCirculationScopeRejectsTypo() {
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .planMeta(Map.of("circulation_scope", "CONNECTED_COMPLES"))
                .build();
        var gap = DesignRequirementAccess.validateCirculationScope(plan);
        assertTrue(gap.isPresent());
        assertEquals("E_CIRCULATION_SCOPE_INVALID", gap.get().code());
    }

    @Test
    void validateCirculationScopeAllowsAbsentKey() {
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true).build();
        assertTrue(DesignRequirementAccess.validateCirculationScope(plan).isEmpty());
    }

    @Test
    void validateCirculationScopeRejectsBlank() {
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .planMeta(Map.of("circulation_scope", "  "))
                .build();
        var gap = DesignRequirementAccess.validateCirculationScope(plan);
        assertTrue(gap.isPresent());
        assertEquals("E_CIRCULATION_SCOPE_INVALID", gap.get().code());
    }

    @Test
    void circulationHostLinksDefaultsToRoot() {
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true).build();
        assertEquals(CirculationHostLinks.ROOT, DesignRequirementAccess.circulationHostLinks(plan));
    }

    @Test
    void circulationHostLinksDirectFromPlanMeta() {
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .planMeta(Map.of("circulation_host_links", "DIRECT"))
                .build();
        assertEquals(CirculationHostLinks.DIRECT, DesignRequirementAccess.circulationHostLinks(plan));
    }

    @Test
    void validateCirculationHostLinksRejectsTypo() {
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .planMeta(Map.of("circulation_host_links", "FOO"))
                .build();
        var gap = DesignRequirementAccess.validateCirculationHostLinks(plan);
        assertTrue(gap.isPresent());
        assertEquals("E_CIRCULATION_HOST_LINKS_INVALID", gap.get().code());
    }
}
