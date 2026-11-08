package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StadiumMisrouteRepairTest {

    @Test
    void stripsStadiumBowlFromBaroqueMansion() {
        Component structure = new Component(
                "STRUCTURE", "main",
                new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 18),
                List.of("typology:stadium_bowl", "elliptical", "ribbon_glazing"),
                Map.of(
                        "component_id", "mansion",
                        "typology_id", "stadium_bowl",
                        "window_aspect", "ribbon_glazing",
                        "elliptical", true,
                        "meshStructure", true));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("Modern_Stadium_Elliptical")
                .components(List.of(structure))
                .planMeta(Map.of(
                        "request_intent_text",
                        "生成一座 15x25 格的双层巴洛克式大宅。顶部为高耸的马萨折线形屋顶与带椭圆山花的老虎窗。"))
                .build();

        LlmPlan out = StadiumMisrouteRepair.apply(plan);
        Component c = out.components().get(0);
        assertEquals("MASS_MAIN", c.componentType());
        assertFalse(String.valueOf(c.params().get("typology_id")).contains("stadium"));
        assertTrue(c.features() == null || c.features().stream().noneMatch(f -> f.contains("stadium")));
        assertEquals("arched", String.valueOf(c.params().get("window_aspect")));
        assertEquals("mansard", String.valueOf(c.params().get("roof_type")));
        assertFalse("Modern_Stadium_Elliptical".equalsIgnoreCase(out.styleProfile()));
        assertTrue(out.components().stream().anyMatch(x ->
                x.componentType() != null && x.componentType().toUpperCase().startsWith("ROOF")));
        Component roof = out.components().stream()
                .filter(x -> x.componentType() != null && x.componentType().toUpperCase().startsWith("ROOF"))
                .findFirst().orElseThrow();
        assertEquals("mansard", String.valueOf(roof.params().get("roof_type")));
    }

    @Test
    void keepsStadiumWhenPromptAsksForArena() {
        Component structure = new Component(
                "STRUCTURE", "main",
                new Vec3i(0, 0, 0),
                new Dimensions(60, 80, 28),
                List.of("typology:stadium_bowl"),
                Map.of("component_id", "bowl", "typology_id", "stadium_bowl"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(structure))
                .planMeta(Map.of("request_intent_text", "生成一座椭圆体育场，带看台"))
                .build();

        LlmPlan out = StadiumMisrouteRepair.apply(plan);
        assertEquals("STRUCTURE", out.components().get(0).componentType());
        assertEquals("stadium_bowl", out.components().get(0).params().get("typology_id"));
    }
}
