package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
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
}
