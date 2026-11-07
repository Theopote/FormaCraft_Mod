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
    void threeStoreyHallDoesNotRaiseSingleStoreyPavilion() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0),
                new Dimensions(15, 11, 12), List.of(),
                Map.of("component_id", "hall_main", "building_role", "hall_main", "floor_count", 3));
        Component pavilion = new Component(
                "MASS_MAIN", "pavilion", new Vec3i(20, 0, 0),
                new Dimensions(7, 7, 5), List.of(),
                Map.of("component_id", "pavilion_1", "building_role", "pavilion", "floor_count", 1));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder()
                        .components(List.of(hall, pavilion))
                        .build(),
                "生成一座三层道观主殿，旁边建一座单层亭子");
        LlmPlan repaired = PlanIntentFidelityRepair.apply(plan);
        Component outHall = findById(repaired, "hall_main");
        Component outPavilion = findById(repaired, "pavilion_1");
        assertEquals(3, ((Number) outHall.params().get("floor_count")).intValue());
        assertEquals(1, ((Number) outPavilion.params().get("floor_count")).intValue(),
                "pavilion must keep single-storey floor_count");
    }

    @Test
    void serverUserTextOverridesWrongLlmOriginalTextEcho() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(new Component(
                        "MASS_MAIN", null, new Vec3i(0, 0, 0),
                        new Dimensions(8, 8, 10), List.of(), Map.of("floor_count", 2))))
                .planMeta(Map.of(
                        "request_intent", Map.of(
                                "original_text", "生成一座两层普通房屋",
                                "building_class", "generic")))
                .build();
        LlmPlan ensured = RequestIntentInjector.ensure(plan, "生成一座三层中式古塔");
        @SuppressWarnings("unchecked")
        Map<String, Object> ri = (Map<String, Object>) ensured.planMeta().get("request_intent");
        assertEquals("生成一座三层中式古塔", ri.get("original_text"));
        assertEquals(Boolean.TRUE, ri.get("intent_echo_mismatch"));
        assertEquals("生成一座两层普通房屋", ri.get("parsed_intent_echo"));
        assertEquals("tower_like", ri.get("building_class"),
                "mismatch must re-extract building_class from server text");
        assertEquals("tower_like", ensured.planMeta().get("building_class"));
        LlmPlan repaired = PlanIntentFidelityRepair.apply(ensured);
        assertEquals(3, ((Number) repaired.components().getFirst().params().get("floor_count")).intValue());
    }

    @Test
    void intentEchoMismatchReclassifiesTempleEchoAsTibetanStupa() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(new Component(
                        "MASS_MAIN", null, new Vec3i(0, 0, 0),
                        new Dimensions(10, 10, 16), List.of(), Map.of())))
                .planMeta(Map.of(
                        "request_intent", Map.of(
                                "original_text", "生成一座寺庙大殿",
                                "building_class", "temple"),
                        "building_class", "temple"))
                .build();
        LlmPlan ensured = RequestIntentInjector.ensure(plan, "生成一座白色藏式佛塔");
        @SuppressWarnings("unchecked")
        Map<String, Object> ri = (Map<String, Object>) ensured.planMeta().get("request_intent");
        assertEquals(Boolean.TRUE, ri.get("intent_echo_mismatch"));
        assertEquals("tower_like", ri.get("building_class"));
        assertEquals("tibetan_stupa", ri.get("tower_subtype"));
        assertEquals("tower_like", ensured.planMeta().get("building_class"));
        assertNotNull(ri.get("stale_parsed_intent"));
    }

    @Test
    void whiteWallBlackTileOverridesWrongLlmWallBlock() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(11, 9, 6), List.of(),
                Map.of(
                        "component_id", "hui_house",
                        "wall_block", "minecraft:oak_planks",
                        "roof_block", "minecraft:oak_stairs"));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().components(List.of(mass)).build(),
                "生成一座徽派民居，白墙黑瓦");
        LlmPlan repaired = PlanIntentFidelityRepair.apply(plan);
        Component out = repaired.components().getFirst();
        assertEquals("minecraft:white_concrete", out.params().get("wall_block"));
        assertEquals("minecraft:black_terracotta", out.params().get("roof_block"));
    }

    @Test
    void whiteWallBlackTileDoesNotRecolorWoodenPavilion() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0),
                new Dimensions(15, 11, 8), List.of(),
                Map.of(
                        "component_id", "hall_main",
                        "building_role", "hall_main",
                        "wall_block", "minecraft:oak_planks",
                        "roof_block", "minecraft:oak_stairs"));
        Component pavilion = new Component(
                "MASS_MAIN", "pavilion", new Vec3i(20, 0, 0),
                new Dimensions(7, 7, 5), List.of(),
                Map.of(
                        "component_id", "pavilion_1",
                        "building_role", "pavilion",
                        "wall_block", "minecraft:oak_log",
                        "roof_block", "minecraft:oak_stairs"));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder()
                        .components(List.of(hall, pavilion))
                        .build(),
                "生成一座江南园林，主厅白墙黑瓦，旁边建一座木结构亭子");
        LlmPlan repaired = PlanIntentFidelityRepair.apply(plan);
        Component outHall = findById(repaired, "hall_main");
        Component outPavilion = findById(repaired, "pavilion_1");
        assertEquals("minecraft:white_concrete", outHall.params().get("wall_block"));
        assertEquals("minecraft:black_terracotta", outHall.params().get("roof_block"));
        assertEquals("minecraft:oak_log", outPavilion.params().get("wall_block"),
                "pavilion must keep local wood material");
        assertEquals("minecraft:oak_stairs", outPavilion.params().get("roof_block"));
    }

    @Test
    void matchingEchoStillFixesWrongBuildingClass() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(new Component(
                        "MASS_MAIN", null, new Vec3i(0, 0, 0),
                        new Dimensions(10, 10, 16), List.of(), Map.of())))
                .planMeta(Map.of(
                        "request_intent", Map.of(
                                "original_text", "生成一座白色藏式佛塔",
                                "building_class", "temple",
                                "footprint", Map.of("width", 20, "depth", 20)),
                        "building_class", "temple"))
                .build();
        LlmPlan ensured = RequestIntentInjector.ensure(plan, "生成一座白色藏式佛塔");
        @SuppressWarnings("unchecked")
        Map<String, Object> ri = (Map<String, Object>) ensured.planMeta().get("request_intent");
        assertEquals("tower_like", ri.get("building_class"));
        assertEquals("tibetan_stupa", ri.get("tower_subtype"));
        assertNull(ri.get("footprint"), "unspecified size must clear stale LLM footprint");
        @SuppressWarnings("unchecked")
        Map<String, Object> mats = (Map<String, Object>) ri.get("materials");
        assertNotNull(mats);
        assertEquals("minecraft:white_concrete", mats.get("wall"));
    }

    @Test
    void playerHeightTwentyFourOverridesLlmFortyAndSeals() {
        Component tower = new Component(
                "STRUCTURE", "main", new Vec3i(0, 0, 0),
                new Dimensions(9, 9, 40), List.of("typology:dense_eaves_pagoda"),
                new HashMap<>(Map.of(
                        "component_id", "pagoda",
                        "floor_count", 3,
                        "eaves_per_level", 2,
                        "height", 40,
                        "design_sources", Map.of("height", "USER_EXPLICIT"))));
        LlmPlan ensured = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().components(List.of(tower)).build(),
                "生成一座高度24格的三层重檐古塔");
        Component out = ensured.components().getFirst();
        assertEquals(24, ((Number) out.params().get("height")).intValue());
        assertTrue(com.formacraft.common.typology.UserConstraintContract
                .isServerVerified(out.params(), "height"));
        var merged = com.formacraft.common.typology.TypologyParamResolver
                .merge("dense_eaves_pagoda", out);
        assertTrue(com.formacraft.common.typology.TypologyParamResolver
                .isUserHardConstraint(merged, "height", "towerHeight"));
        assertEquals(24, ((Number) merged.get("height")).intValue());
    }

    @Test
    void weakKeywordGenericDoesNotDemoteLlmTempleClass() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(new Component(
                        "MASS_MAIN", null, new Vec3i(0, 0, 0),
                        new Dimensions(12, 10, 8), List.of(), Map.of())))
                .planMeta(Map.of(
                        "request_intent", Map.of(
                                "original_text", "生成一座清真寺",
                                "building_class", "temple",
                                "confidence", 0.8),
                        "building_class", "temple"))
                .build();
        LlmPlan ensured = RequestIntentInjector.ensure(plan, "生成一座清真寺");
        @SuppressWarnings("unchecked")
        Map<String, Object> ri = (Map<String, Object>) ensured.planMeta().get("request_intent");
        assertEquals("temple", ri.get("building_class"),
                "weak/generic keyword must not demote LLM temple class");
    }

    @Test
    void upperEntranceRequiredButOnlyGroundDoorFailsAudit() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of(),
                Map.of("component_id", "house", "floor_count", 2, "floor_height", 4));
        Component groundDoor = new Component(
                "ENTRANCE", "main", new Vec3i(4, 0, 0),
                new Dimensions(3, 1, 3), List.of(),
                Map.of("host_id", "house", "door_width", 2));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().components(List.of(mass, groundDoor)).build(),
                "二层设置一个连接室外楼梯的入口");
        var gap = UpperStoreyEntranceAudit.check(plan);
        assertTrue(gap.isPresent(), "must fail when only a ground door exists");
        assertEquals("E_UPPER_ENTRANCE_MISSING", gap.orElseThrow().code());
    }

    @Test
    void sideHallMainEntranceDoesNotReceivePrimaryDoorWidth() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0),
                new Dimensions(15, 11, 9), List.of(),
                Map.of("component_id", "hall_main", "building_role", "hall_main"));
        Component hallDoor = new Component(
                "ENTRANCE", "main_entrance", new Vec3i(6, 0, 11),
                new Dimensions(3, 1, 3), List.of(),
                Map.of("entrance_role", "main", "host_id", "hall_main", "door_width", 1));
        Component sideHall = new Component(
                "MASS_MAIN", "side", new Vec3i(20, 0, 0),
                new Dimensions(9, 7, 6), List.of(),
                Map.of("component_id", "side_hall", "building_role", "secondary"));
        Component sideDoor = new Component(
                "ENTRANCE", "main_entrance", new Vec3i(23, 0, 7),
                new Dimensions(2, 1, 3), List.of(),
                Map.of("entrance_role", "main", "host_id", "side_hall", "door_width", 1));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder()
                        .components(List.of(hall, hallDoor, sideHall, sideDoor))
                        .planMeta(Map.of("primary_building_id", "hall_main"))
                        .build(),
                "主殿正门宽三格");
        LlmPlan repaired = PlanIntentFidelityRepair.apply(plan);
        Component outHallDoor = repaired.components().stream()
                .filter(c -> "ENTRANCE".equalsIgnoreCase(c.componentType()))
                .filter(c -> "hall_main".equals(String.valueOf(c.params().get("host_id"))))
                .findFirst().orElseThrow();
        Component outSideDoor = repaired.components().stream()
                .filter(c -> "ENTRANCE".equalsIgnoreCase(c.componentType()))
                .filter(c -> "side_hall".equals(String.valueOf(c.params().get("host_id"))))
                .findFirst().orElseThrow();
        assertEquals(3, ((Number) outHallDoor.params().get("door_width")).intValue());
        assertEquals(1, ((Number) outSideDoor.params().get("door_width")).intValue(),
                "side-hall main entrance must not inherit hall 正门 width");
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
