package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TempleSideHallMisrouteRepairTest {

    @Test
    void recoversClassicalDegradeWithPeidianAndYuetai() {
        Component hall = new Component(
                "MASS_MAIN", "body", new Vec3i(0, 0, 0),
                new Dimensions(32, 40, 19), List.of("pilasters", "base_plinth", "cornice"),
                Map.of("component_id", "component_1", "facade_profile", "vertical_pilasters",
                        "anchor_mode", "min_corner"));
        Component roof = new Component(
                "ROOF", "body", new Vec3i(-2, 19, -2),
                new Dimensions(36, 44, 5), List.of("overhang"),
                Map.of("component_id", "component_2", "host_id", "component_1",
                        "roof_type", "hip", "anchor_mode", "min_corner"));
        Component windows = new Component(
                "FACADE_WINDOWS", "body", new Vec3i(0, 0, 0),
                new Dimensions(32, 1, 4), List.of(),
                Map.of("component_id", "component_3", "host_id", "component_1",
                        "rhythm_preset", "CLASSICAL_PILASTER_BAY"));
        Component entrance = new Component(
                "ENTRANCE", "body", new Vec3i(0, 0, 0),
                new Dimensions(32, 24, 4), List.of(),
                Map.of("component_id", "component_4", "host_id", "component_1",
                        "door_width", 30, "door_height", 4));
        Component cornice = new Component(
                "DECOR_DETAIL", "body", new Vec3i(0, 18, 0),
                new Dimensions(32, 40, 2), List.of("cornice", "pattern", "carved"),
                Map.of("component_id", "component_6", "host_id", "component_1"));
        Component crown = new Component(
                "CROWN", "body", new Vec3i(10, 24, 14),
                new Dimensions(13, 13, 10), List.of("crown"),
                Map.of("component_id", "component_8", "host_id", "component_1",
                        "crown_template", "CLASSICAL_CUPOLA"));

        String prompt = "生成一座重檐歇山顶大雄宝殿，前有月台，左右各一配殿，配殿用悬山顶，主殿必须是重檐歇山。";
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("DEFAULT")
                .components(List.of(hall, roof, windows, entrance, cornice, crown))
                .proportionHints(Map.of("typology", "classical_monument", "crown_assembly", true))
                .planMeta(Map.of(
                        "request_intent", Map.of(
                                "original_text", prompt,
                                "building_class", "generic",
                                "roof_type", "double_eave_xieshan"),
                        "request_intent_text", prompt))
                .build();

        LlmPlan repaired = TempleSideHallMisrouteRepair.apply(plan);

        assertEquals("chinese_temple_imperial", repaired.styleProfile());
        assertTrue(repaired.components().stream().noneMatch(c -> "CROWN".equalsIgnoreCase(c.componentType())));
        assertTrue(repaired.components().stream().noneMatch(c ->
                "DECOR_DETAIL".equalsIgnoreCase(c.componentType())
                        && c.features() != null
                        && c.features().stream().anyMatch(f -> f != null && f.contains("cornice"))));

        Component outRoof = repaired.components().stream()
                .filter(c -> "ROOF".equalsIgnoreCase(c.componentType())
                        && "component_1".equals(String.valueOf(c.params().get("host_id"))))
                .findFirst().orElseThrow();
        assertEquals("xieshan", String.valueOf(outRoof.params().get("roof_type")));
        assertEquals(Boolean.TRUE, outRoof.params().get("double_eave"));

        long peidian = repaired.components().stream()
                .filter(c -> c.params() != null
                        && String.valueOf(c.params().get("component_id")).contains("peidian")
                        && c.componentType() != null
                        && c.componentType().startsWith("MASS"))
                .count();
        assertEquals(2, peidian);

        assertTrue(repaired.components().stream().anyMatch(c ->
                c.params() != null && "temple_yuetai".equals(String.valueOf(c.params().get("component_id")))));

        Component outDoor = repaired.components().stream()
                .filter(c -> "ENTRANCE".equalsIgnoreCase(c.componentType()))
                .findFirst().orElseThrow();
        int doorW = ((Number) outDoor.params().get("door_width")).intValue();
        assertTrue(doorW >= 3 && doorW <= 7, () -> "door_width=" + doorW);

        Component outWin = repaired.components().stream()
                .filter(c -> "FACADE_WINDOWS".equalsIgnoreCase(c.componentType()))
                .findFirst().orElseThrow();
        assertEquals(true, outWin.params().get("wrap"));
    }

    @Test
    void extractKeywordMarksDaxiongBaodianAsTempleCompound() {
        Map<String, Object> intent = RequestIntentInjector.extract(
                "生成一座重檐歇山顶大雄宝殿，前有月台，左右各一配殿，配殿用悬山顶，主殿必须是重檐歇山。");
        assertEquals("temple", intent.get("building_class"));
        assertEquals("compound", intent.get("composition"));
        assertEquals("double_eave_xieshan", intent.get("roof_type"));
        assertTrue(((Number) intent.get("confidence")).doubleValue() >= 0.8);
    }

    @Test
    void interiorFloorDoesNotSatisfyMoonPlatform() {
        Component hall = new Component(
                "MASS_MAIN", "body", new Vec3i(0, 0, 0),
                new Dimensions(20, 16, 12), List.of("main_hall"),
                Map.of("component_id", "temple_hall", "anchor_mode", "min_corner"));
        Component floor = new Component(
                "MASS_SECONDARY", "body", new Vec3i(1, 4, 1),
                new Dimensions(18, 14, 1), List.of("floor_plate"),
                Map.of("component_id", "main_floor_plate", "host_id", "temple_hall",
                        "extrude_mode", "plate", "plate_role", "floor",
                        "anchor_mode", "min_corner"));
        String prompt = "生成一座大雄宝殿，前有月台，左右各一配殿。";
        LlmPlan repaired = TempleSideHallMisrouteRepair.apply(LlmPlanTestFixtures.builder()
                .styleProfile("DEFAULT")
                .components(List.of(hall, floor))
                .planMeta(Map.of(
                        "request_intent", Map.of("original_text", prompt),
                        "request_intent_text", prompt))
                .build());
        assertTrue(repaired.components().stream().anyMatch(c ->
                c.params() != null && "main_floor_plate".equals(String.valueOf(c.params().get("component_id")))));
        assertTrue(repaired.components().stream().anyMatch(c ->
                c.params() != null && "temple_yuetai".equals(String.valueOf(c.params().get("component_id")))),
                "interior floor plate must not suppress 月台 injection");
    }

    @Test
    void sideHallsMustOccupyOppositeSides() {
        Component hall = new Component(
                "MASS_MAIN", "body", new Vec3i(0, 0, 0),
                new Dimensions(20, 16, 12), List.of("main_hall"),
                Map.of("component_id", "temple_hall", "anchor_mode", "min_corner"));
        // Both "配殿" stacked on the west — must not count as 左右配殿.
        Component leftA = new Component(
                "MASS_SECONDARY", "body", new Vec3i(-12, 0, 2),
                new Dimensions(8, 10, 8), List.of("配殿", "side_hall"),
                Map.of("component_id", "left_peidian_a", "building_role", "side_hall",
                        "anchor_mode", "min_corner"));
        Component leftB = new Component(
                "MASS_SECONDARY", "body", new Vec3i(-12, 0, 4),
                new Dimensions(8, 10, 8), List.of("配殿", "side_hall"),
                Map.of("component_id", "left_peidian_b", "building_role", "side_hall",
                        "anchor_mode", "min_corner"));
        String prompt = "生成一座大雄宝殿，左右各一配殿。";
        LlmPlan repaired = TempleSideHallMisrouteRepair.apply(LlmPlanTestFixtures.builder()
                .styleProfile("chinese_temple_imperial")
                .components(List.of(hall, leftA, leftB))
                .planMeta(Map.of(
                        "request_intent", Map.of("original_text", prompt),
                        "request_intent_text", prompt))
                .build());
        boolean hasEast = repaired.components().stream().anyMatch(c -> {
            if (c.params() == null || c.relativePosition() == null || c.dimensions() == null) return false;
            String id = String.valueOf(c.params().get("component_id"));
            if (!id.contains("peidian") && !id.contains("side")) return false;
            if (!c.componentType().startsWith("MASS")) return false;
            int cx = c.relativePosition().x() + c.dimensions().width() / 2;
            return cx >= hall.relativePosition().x() + hall.dimensions().width();
        });
        assertTrue(hasEast, "same-side pair must trigger opposite-side 配殿 recovery");
    }

    @Test
    void eastFacingHallRequiresNorthSouthSideHallPair() {
        Component hall = new Component(
                "MASS_MAIN", "body", new Vec3i(0, 0, 0),
                new Dimensions(20, 16, 12), List.of("main_hall"),
                Map.of("component_id", "temple_hall", "anchor_mode", "min_corner"));
        // World E-W pair looks opposite, but with facing=EAST left/right are N-S — inject.
        Component west = new Component(
                "MASS_SECONDARY", "body", new Vec3i(-12, 0, 2),
                new Dimensions(8, 10, 8), List.of("配殿", "side_hall"),
                Map.of("component_id", "west_peidian", "building_role", "side_hall",
                        "anchor_mode", "min_corner"));
        Component east = new Component(
                "MASS_SECONDARY", "body", new Vec3i(22, 0, 2),
                new Dimensions(8, 10, 8), List.of("配殿", "side_hall"),
                Map.of("component_id", "east_peidian", "building_role", "side_hall",
                        "anchor_mode", "min_corner"));
        String prompt = "生成一座大雄宝殿，左右各一配殿。";
        LlmPlan repaired = TempleSideHallMisrouteRepair.apply(LlmPlanTestFixtures.builder()
                .styleProfile("chinese_temple_imperial")
                .globalConstraints(new GlobalConstraints(
                        GlobalConstraints.Facing.EAST,
                        GlobalConstraints.Symmetry.NONE,
                        GlobalConstraints.TerrainStrategy.ADAPTIVE))
                .components(List.of(hall, west, east))
                .planMeta(Map.of(
                        "request_intent", Map.of("original_text", prompt),
                        "request_intent_text", prompt))
                .build());
        Component left = repaired.components().stream()
                .filter(c -> "left_peidian".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        Component right = repaired.components().stream()
                .filter(c -> "right_peidian".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        int hallMinZ = hall.relativePosition().z();
        int hallMaxZ = hallMinZ + hall.dimensions().depth();
        int leftCz = left.relativePosition().z() + left.dimensions().depth() / 2;
        int rightCz = right.relativePosition().z() + right.dimensions().depth() / 2;
        assertTrue(leftCz < hallMinZ, "east-facing left 配殿 must sit north of the hall");
        assertTrue(rightCz >= hallMaxZ, "east-facing right 配殿 must sit south of the hall");
    }

    @Test
    void westFacingHallPlacesLeftSouthAndRightNorth() {
        Component hall = new Component(
                "MASS_MAIN", "body", new Vec3i(0, 0, 0),
                new Dimensions(20, 16, 12), List.of("main_hall"),
                Map.of("component_id", "temple_hall", "anchor_mode", "min_corner"));
        String prompt = "生成一座大雄宝殿，左右各一配殿。";
        LlmPlan repaired = TempleSideHallMisrouteRepair.apply(LlmPlanTestFixtures.builder()
                .styleProfile("chinese_temple_imperial")
                .globalConstraints(new GlobalConstraints(
                        GlobalConstraints.Facing.WEST,
                        GlobalConstraints.Symmetry.NONE,
                        GlobalConstraints.TerrainStrategy.ADAPTIVE))
                .components(List.of(hall))
                .planMeta(Map.of(
                        "request_intent", Map.of("original_text", prompt),
                        "request_intent_text", prompt))
                .build());
        Component left = repaired.components().stream()
                .filter(c -> "left_peidian".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        Component right = repaired.components().stream()
                .filter(c -> "right_peidian".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        int hallMinZ = hall.relativePosition().z();
        int hallMaxZ = hallMinZ + hall.dimensions().depth();
        int leftCz = left.relativePosition().z() + left.dimensions().depth() / 2;
        int rightCz = right.relativePosition().z() + right.dimensions().depth() / 2;
        assertTrue(leftCz >= hallMaxZ, "west-facing left 配殿 must sit south of the hall");
        assertTrue(rightCz < hallMinZ, "west-facing right 配殿 must sit north of the hall");
    }

    @Test
    void chineseCarvedDecorSurvivesRepair() {
        Component hall = new Component(
                "MASS_MAIN", "body", new Vec3i(0, 0, 0),
                new Dimensions(20, 16, 12), List.of("main_hall"),
                Map.of("component_id", "temple_hall", "anchor_mode", "min_corner"));
        Component beam = new Component(
                "DECOR_DETAIL", "body", new Vec3i(2, 10, 2),
                new Dimensions(16, 1, 1), List.of("carved_beam", "雕梁"),
                Map.of("component_id", "carved_beam_main", "host_id", "temple_hall"));
        Component dougong = new Component(
                "DECOR_DETAIL", "body", new Vec3i(1, 11, 1),
                new Dimensions(18, 14, 2), List.of("dougong", "斗拱"),
                Map.of("component_id", "dougong_main", "host_id", "temple_hall"));
        Component cornice = new Component(
                "DECOR_DETAIL", "body", new Vec3i(0, 11, 0),
                new Dimensions(20, 16, 1), List.of("cornice", "classical"),
                Map.of("component_id", "classical_cornice", "host_id", "temple_hall"));
        String prompt = "生成一座大雄宝殿，左右各一配殿，保留雕梁斗拱。";
        LlmPlan repaired = TempleSideHallMisrouteRepair.apply(LlmPlanTestFixtures.builder()
                .styleProfile("DEFAULT")
                .components(List.of(hall, beam, dougong, cornice))
                .proportionHints(Map.of("typology", "classical_monument"))
                .planMeta(Map.of(
                        "request_intent", Map.of("original_text", prompt),
                        "request_intent_text", prompt))
                .build());
        assertTrue(repaired.components().stream().anyMatch(c ->
                "carved_beam_main".equals(String.valueOf(c.params().get("component_id")))));
        assertTrue(repaired.components().stream().anyMatch(c ->
                "dougong_main".equals(String.valueOf(c.params().get("component_id")))));
        assertTrue(repaired.components().stream().noneMatch(c ->
                "classical_cornice".equals(String.valueOf(c.params().get("component_id")))));
    }
}
