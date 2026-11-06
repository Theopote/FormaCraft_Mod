package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch B: primary host resolution + per-host roof / double-eave binding.
 */
class BuildingHostRoofBindingTest {

    @Test
    void primaryMassIgnoresComponentOrderWhenPavilionListedFirst() {
        Component pavilion = new Component(
                "MASS_MAIN", "pavilion", new Vec3i(20, 0, 0),
                new Dimensions(7, 7, 6), List.of(),
                Map.of("component_id", "pavilion_1", "building_role", "pavilion"));
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0),
                new Dimensions(15, 11, 9), List.of(),
                Map.of("component_id", "hall_main", "building_role", "hall_main"));
        String primary = BuildingHostResolver.primaryMassId(null, List.of(pavilion, hall));
        assertEquals("hall_main", primary);
    }

    @Test
    void explicitPrimaryBuildingIdWinsOverOrder() {
        Component wing = new Component(
                "MASS_MAIN", "wing", new Vec3i(0, 0, 0),
                new Dimensions(20, 12, 10), List.of(),
                Map.of("component_id", "side_wing"));
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(30, 0, 0),
                new Dimensions(12, 10, 8), List.of(),
                Map.of("component_id", "hall_main"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(wing, hall))
                .planMeta(Map.of("primary_building_id", "hall_main"))
                .build();
        assertEquals("hall_main", BuildingHostResolver.primaryMassId(plan, plan.components()));
    }

    @Test
    void pavilionDoubleEaveCannotProvePrimaryHallMissingDoubleEave() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0),
                new Dimensions(15, 11, 9), List.of(),
                Map.of("component_id", "hall_main"));
        Component hallRoof = new Component(
                "ROOF", "hall", new Vec3i(0, 9, 0),
                new Dimensions(17, 13, 4), List.of(),
                Map.of("roof_type", "hip", "host_id", "hall_main"));
        Component pavilion = new Component(
                "MASS_MAIN", "pavilion", new Vec3i(20, 0, 0),
                new Dimensions(7, 7, 6), List.of(),
                Map.of("component_id", "pavilion_1"));
        Component pavilionRoof = new Component(
                "ROOF", "pavilion", new Vec3i(20, 6, 0),
                new Dimensions(9, 9, 3), List.of("double_eave"),
                Map.of(
                        "roof_type", "pyramid",
                        "double_eave", true,
                        "host_id", "pavilion_1"));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder()
                        .components(List.of(pavilion, pavilionRoof, hall, hallRoof))
                        .build(),
                "生成重檐庑殿顶主殿，旁有攒尖亭");
        Optional<CapabilityGap> gap = PlanSemanticValidator.check(plan);
        assertTrue(gap.isPresent());
        assertEquals("E_ROOF_INTENT_CONFLICT", gap.get().code());
    }

    @Test
    void unboundAccessoryDoubleEaveCannotMaskPrimaryPlainRoof() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0),
                new Dimensions(15, 11, 9), List.of(),
                Map.of("component_id", "hall_main"));
        Component hallRoof = new Component(
                "ROOF", "hall", new Vec3i(0, 9, 0),
                new Dimensions(17, 13, 4), List.of(),
                Map.of("roof_type", "hip", "host_id", "hall_main"));
        Component pavilion = new Component(
                "MASS_MAIN", "pavilion", new Vec3i(20, 0, 0),
                new Dimensions(7, 7, 6), List.of(),
                Map.of("component_id", "pavilion_1"));
        Component unboundDouble = new Component(
                "ROOF", "pavilion", new Vec3i(20, 6, 0),
                new Dimensions(9, 9, 3), List.of("double_eave"),
                Map.of("roof_type", "double_eave_hip")); // no host_id
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder()
                        .components(List.of(hall, hallRoof, pavilion, unboundDouble))
                        .build(),
                "生成重檐庑殿顶大殿");
        Optional<CapabilityGap> gap = PlanSemanticValidator.check(plan);
        assertTrue(gap.isPresent());
        assertEquals("E_ROOF_INTENT_CONFLICT", gap.get().code());
    }

    @Test
    void primaryHallWithDoubleEavePassesDespitePavilionPyramid() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0),
                new Dimensions(15, 11, 9), List.of(),
                Map.of("component_id", "hall_main"));
        Component hallRoof = new Component(
                "ROOF", "hall", new Vec3i(0, 9, 0),
                new Dimensions(17, 13, 4), List.of("double_eave"),
                Map.of(
                        "roof_type", "double_eave_hip",
                        "eave_configuration", "DOUBLE",
                        "host_id", "hall_main"));
        Component pavilion = new Component(
                "MASS_MAIN", "pavilion", new Vec3i(20, 0, 0),
                new Dimensions(7, 7, 6), List.of(),
                Map.of("component_id", "pavilion_1"));
        Component pavilionRoof = new Component(
                "ROOF", "pavilion", new Vec3i(20, 6, 0),
                new Dimensions(9, 9, 3), List.of(),
                Map.of("roof_type", "pyramid", "host_id", "pavilion_1"));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder()
                        .components(List.of(pavilion, pavilionRoof, hall, hallRoof))
                        .build(),
                "生成重檐庑殿顶主殿，旁边一座攒尖亭");
        Optional<CapabilityGap> gap = PlanSemanticValidator.check(plan);
        assertTrue(gap.isEmpty(), () -> gap.map(CapabilityGap::message).orElse("ok"));
    }
}
