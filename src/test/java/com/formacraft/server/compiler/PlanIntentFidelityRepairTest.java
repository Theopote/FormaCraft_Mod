package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-14 fidelity: explicit footprints, request_intent corpus, double-eave host scope.
 */
class PlanIntentFidelityRepairTest {

    @Test
    void threeStoreySevenBySevenMassIsNotExpandedToOfficePlate() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(-3, 0, -3),
                new Dimensions(7, 7, 12), List.of(),
                Map.of("floor_count", 3, "component_id", "main_body"));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder()
                        .components(List.of(mass))
                        .proportionHints(Map.of("architecture_intent", "三层中式楼阁 7×7"))
                        .build(),
                "生成一座三层中式楼阁，平面约 7×7");
        LlmPlan repaired = PlanIntentFidelityRepair.apply(plan);
        Component out = repaired.components().getFirst();
        assertEquals(7, out.dimensions().width(), "width must stay explicit 7");
        assertEquals(7, out.dimensions().depth(), "depth must stay explicit 7");
    }

    @Test
    void intentCorpusPrefersRequestIntentOriginalText() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(new Component(
                        "MASS_MAIN", null, new Vec3i(0, 0, 0),
                        new Dimensions(8, 8, 10), List.of(), Map.of())))
                .planMeta(Map.of(
                        "request_intent", Map.of(
                                "original_text", "三层重檐古塔顶部有塔刹",
                                "building_class", "tower_like")))
                .proportionHints(Map.of("architecture_intent", "普通房屋"))
                .build();
        String corpus = PlanIntentFidelityRepair.intentCorpus(plan).toLowerCase();
        assertTrue(corpus.contains("重檐"), corpus);
        assertTrue(corpus.contains("塔刹") || corpus.contains("古塔"), corpus);
        int towerIdx = Math.max(corpus.indexOf("古塔"), corpus.indexOf("重檐"));
        int houseIdx = corpus.indexOf("普通房屋");
        assertTrue(towerIdx >= 0 && (houseIdx < 0 || towerIdx < houseIdx),
                "request_intent text should appear before LLM architecture_intent");
    }

    @Test
    void doubleEaveRepairDoesNotStampAccessoryPavilion() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0),
                new Dimensions(15, 11, 9), List.of(),
                Map.of("component_id", "hall_main", "building_role", "hall_main"));
        Component hallRoof = new Component(
                "ROOF", "hall", new Vec3i(0, 9, 0),
                new Dimensions(17, 13, 4), List.of(),
                Map.of("roof_type", "hip", "host_id", "hall_main"));
        Component pavilion = new Component(
                "MASS_MAIN", "pavilion", new Vec3i(20, 0, 0),
                new Dimensions(7, 7, 6), List.of(),
                Map.of("component_id", "pavilion_1", "building_role", "pavilion"));
        Component pavilionRoof = new Component(
                "ROOF", "pavilion", new Vec3i(20, 6, 0),
                new Dimensions(9, 9, 3), List.of(),
                Map.of("roof_type", "pyramid", "host_id", "pavilion_1"));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder()
                        .components(List.of(hall, hallRoof, pavilion, pavilionRoof))
                        .build(),
                "生成一座重檐庑殿顶主殿，旁边有一座单檐攒尖亭");
        LlmPlan repaired = PlanIntentFidelityRepair.apply(plan);

        Component outHallRoof = findByHost(repaired, "hall_main");
        Component outPavilionRoof = findByHost(repaired, "pavilion_1");
        Component outPavilion = findById(repaired, "pavilion_1");

        assertTrue(Boolean.TRUE.equals(outHallRoof.params().get("double_eave"))
                        || (outHallRoof.features() != null
                        && outHallRoof.features().stream().anyMatch(f -> f.contains("double_eave"))),
                "primary hall roof should receive double_eave");
        assertFalse(Boolean.TRUE.equals(outPavilionRoof.params().get("double_eave")),
                "pavilion roof must not inherit hall double_eave");
        assertFalse(Boolean.TRUE.equals(outPavilion.params().get("double_eave")),
                "pavilion mass must not inherit hall double_eave");
        assertTrue(outPavilionRoof.features() == null
                        || outPavilionRoof.features().stream().noneMatch(f -> f.contains("double_eave")),
                "pavilion roof features must stay single-eave");
    }

    @Test
    void userThreeStoreyOverridesWrongLlmTwoStoreySingleEave() {
        Component mass = new Component(
                "STRUCTURE", "tower", new Vec3i(0, 0, 0),
                new Dimensions(9, 9, 20), List.of("typology:dense_eaves_pagoda"),
                Map.of(
                        "component_id", "pagoda_main",
                        "floor_count", 2,
                        "eaves_per_level", 1,
                        "footprint", "octagon"));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder()
                        .components(List.of(mass))
                        .proportionHints(Map.of("architecture_intent", "两层建筑 单檐"))
                        .build(),
                "生成一座三层中式古塔，重檐");
        LlmPlan repaired = PlanIntentFidelityRepair.apply(plan);
        Component out = repaired.components().getFirst();
        assertEquals(3, ((Number) out.params().get("floor_count")).intValue());
        assertTrue(Boolean.TRUE.equals(out.params().get("double_eave"))
                        || Integer.valueOf(2).equals(out.params().get("eave_tiers"))
                        || Integer.valueOf(2).equals(out.params().get("eaves_per_level")),
                "user 重檐 must correct LLM single-eave; params=" + out.params());
    }

    @Test
    void unboundAccessoryRoofDoesNotReceivePrimaryDoubleEave() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0),
                new Dimensions(15, 11, 9), List.of(),
                Map.of("component_id", "hall_main", "building_role", "hall_main"));
        Component hallRoof = new Component(
                "ROOF", "hall", new Vec3i(0, 9, 0),
                new Dimensions(17, 13, 4), List.of(),
                Map.of("roof_type", "hip", "host_id", "hall_main"));
        Component pavilion = new Component(
                "MASS_MAIN", "pavilion", new Vec3i(20, 0, 0),
                new Dimensions(7, 7, 6), List.of(),
                Map.of("component_id", "pavilion_1", "building_role", "pavilion"));
        Component unboundRoof = new Component(
                "ROOF", "pavilion", new Vec3i(20, 6, 0),
                new Dimensions(9, 9, 3), List.of(),
                Map.of("roof_type", "pyramid")); // no host_id
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder()
                        .components(List.of(hall, hallRoof, pavilion, unboundRoof))
                        .build(),
                "生成一座重檐庑殿顶主殿，旁边有一座单檐攒尖亭");
        LlmPlan repaired = PlanIntentFidelityRepair.apply(plan);
        Component outUnbound = repaired.components().stream()
                .filter(c -> "ROOF".equalsIgnoreCase(c.componentType()))
                .filter(c -> c.params() == null || c.params().get("host_id") == null)
                .findFirst()
                .orElseThrow();
        assertFalse(Boolean.TRUE.equals(outUnbound.params().get("double_eave")),
                "unbound accessory roof must not inherit primary double_eave");
    }

    @Test
    void mainDoorWidthThreeDoesNotWidenSideEntrance() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(11, 9, 6), List.of(),
                Map.of("component_id", "house_main"));
        Component mainDoor = new Component(
                "ENTRANCE", "main_entrance", new Vec3i(4, 0, 9),
                new Dimensions(3, 1, 3), List.of(),
                Map.of("entrance_role", "main", "host_id", "house_main", "door_width", 1));
        Component sideDoor = new Component(
                "ENTRANCE", "side_entrance", new Vec3i(11, 0, 4),
                new Dimensions(1, 2, 3), List.of(),
                Map.of("entrance_role", "side", "host_id", "house_main", "door_width", 1));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder()
                        .components(List.of(mass, mainDoor, sideDoor))
                        .build(),
                "生成一栋徽派民居，正门宽三格，侧门保持普通单门");
        LlmPlan repaired = PlanIntentFidelityRepair.apply(plan);
        Component outMain = repaired.components().stream()
                .filter(c -> "main_entrance".equals(c.slotId()))
                .findFirst().orElseThrow();
        Component outSide = repaired.components().stream()
                .filter(c -> "side_entrance".equals(c.slotId()))
                .findFirst().orElseThrow();
        assertEquals(3, ((Number) outMain.params().get("door_width")).intValue());
        assertEquals(1, ((Number) outSide.params().get("door_width")).intValue(),
                "side entrance must keep single-leaf width");
    }

    @Test
    void lockedShallowRoofHeightIsNotDropped() {
        Map<String, Object> params = new HashMap<>();
        params.put("roof_height", 2);
        params.put("design_sources", Map.of("roof_height", "USER_EXPLICIT"));
        Component roof = new Component(
                "ROOF", null, new Vec3i(0, 8, 0),
                new Dimensions(12, 10, 3), List.of(), params);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(
                        new Component("MASS_MAIN", null, new Vec3i(0, 0, 0),
                                new Dimensions(12, 10, 8), List.of(), Map.of()),
                        roof))
                .proportionHints(Map.of("architecture_intent", "楼梯坡屋面"))
                .build();
        LlmPlan repaired = PlanIntentFidelityRepair.apply(plan);
        Component outRoof = repaired.components().stream()
                .filter(c -> "ROOF".equalsIgnoreCase(c.componentType()))
                .findFirst()
                .orElseThrow();
        assertEquals(2, ((Number) outRoof.params().get("roof_height")).intValue());
    }

    private static Component findByHost(LlmPlan plan, String hostId) {
        return plan.components().stream()
                .filter(c -> c.params() != null && hostId.equals(String.valueOf(c.params().get("host_id"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing host " + hostId));
    }

    private static Component findById(LlmPlan plan, String id) {
        return plan.components().stream()
                .filter(c -> c.params() != null && id.equals(String.valueOf(c.params().get("component_id"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing id " + id));
    }
}
