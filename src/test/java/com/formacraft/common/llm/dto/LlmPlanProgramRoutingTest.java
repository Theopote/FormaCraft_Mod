package com.formacraft.common.llm.dto;

import com.formacraft.common.mass.integration.BuildingMassSystemIntegrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmPlanProgramRoutingTest {
    @AfterEach
    void clearProp() {
        System.clearProperty("formacraft.useBuildingMass");
    }

    @Test
    void emptySkeletonZonesIsInvalidProgramContent() {
        var emptySkeleton = new PlanSkeleton("v1", null, List.of(), List.of(), List.of(), List.of());
        var plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .planSkeleton(emptySkeleton)
                .build();
        assertTrue(plan.usesPlanProgramMode());
        assertFalse(plan.hasValidPlanProgramContent());
        assertFalse(plan.usesComponentMode());
    }

    @Test
    void nonEmptyZonesIsValidProgramContent() {
        var zone = new PlanSkeleton.PlanZone("hall", "external", "public_", List.of());
        var skeleton = new PlanSkeleton("v1", null, List.of(zone), List.of(), List.of(), List.of());
        var plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .planSkeleton(skeleton)
                .build();
        assertTrue(plan.hasValidPlanProgramContent());
    }

    @Test
    void componentsAllowExplicitDegradeWhenProgramEmpty() {
        var emptySkeleton = new PlanSkeleton("v1", null, List.of(), List.of(), List.of(), List.of());
        var component = new Component("MASS_MAIN", "slot0", new Vec3i(0, 0, 0), null, null, null);
        var plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .planSkeleton(emptySkeleton)
                .components(List.of(component))
                .build();
        assertTrue(plan.usesPlanProgramMode());
        assertFalse(plan.hasValidPlanProgramContent());
        assertTrue(plan.usesComponentMode());
    }

    @Test
    void buildingMassNotAutoEnabledByManyZones() {
        var zones = List.of(
                new PlanSkeleton.PlanZone("a", "external", "public_", List.of()),
                new PlanSkeleton.PlanZone("b", "external", "public_", List.of()),
                new PlanSkeleton.PlanZone("c", "external", "public_", List.of())
        );
        var skeleton = new PlanSkeleton("v1", null, zones, List.of(), List.of(), List.of());
        var plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .planSkeleton(skeleton)
                .globalConstraints(new GlobalConstraints(
                        GlobalConstraints.Facing.SOUTH,
                        GlobalConstraints.Symmetry.NONE,
                        GlobalConstraints.TerrainStrategy.ADAPTIVE,
                        null))
                .build();
        assertFalse(BuildingMassSystemIntegrator.shouldUseBuildingMassPath(plan));
    }

    @Test
    void buildingMassEnabledByExplicitConstraint() {
        var zone = new PlanSkeleton.PlanZone("hall", "external", "public_", List.of());
        var skeleton = new PlanSkeleton("v1", null, List.of(zone), List.of(), List.of(), List.of());
        var plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .planSkeleton(skeleton)
                .globalConstraints(new GlobalConstraints(
                        GlobalConstraints.Facing.SOUTH,
                        GlobalConstraints.Symmetry.NONE,
                        GlobalConstraints.TerrainStrategy.ADAPTIVE,
                        true))
                .build();
        assertTrue(BuildingMassSystemIntegrator.shouldUseBuildingMassPath(plan));
    }
}
