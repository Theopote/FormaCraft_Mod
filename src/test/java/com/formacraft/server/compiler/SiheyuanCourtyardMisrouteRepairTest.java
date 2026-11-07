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

class SiheyuanCourtyardMisrouteRepairTest {

    @Test
    void threeJinSiheyuanKeepsCompositionalPlan() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("Chinese_Vernacular_Siheyuan")
                .planMeta(Map.of(
                        "request_intent_text",
                        "生成一座三进四合院，正房、东西厢房、倒座齐全，院落可走通，正房高于厢房，白墙灰瓦。"))
                .components(List.of(
                        mass("north_hall", -8, -18, 16, 6, 6),
                        mass("east_wing", 8, -10, 6, 14, 4),
                        mass("west_wing", -14, -10, 6, 14, 4),
                        mass("dao_zuo", -6, 8, 12, 5, 4),
                        mass("extra_shed", 20, 20, 8, 8, 3)))
                .build();
        LlmPlan out = SiheyuanCourtyardMisrouteRepair.apply(plan);
        assertSame(plan, out, "三进 must not be silently demoted to single courtyard_compound");
        assertTrue(out.components().size() >= 4, "compositional masses must remain");
    }

    @Test
    void incompleteSingleJinWithoutCourtStillRewrites() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("Chinese_Vernacular_Siheyuan")
                .planMeta(Map.of("request_intent_text", "生成一座四合院，正房和厢房"))
                .components(List.of(
                        mass("main", 0, 0, 12, 8, 6),
                        mass("east", 14, 0, 6, 10, 4),
                        mass("west", -8, 0, 6, 10, 4)))
                .build();
        LlmPlan out = SiheyuanCourtyardMisrouteRepair.apply(plan);
        assertEquals(1, out.components().size());
        assertEquals("STRUCTURE", out.components().getFirst().componentType());
        assertEquals("courtyard_compound",
                String.valueOf(out.components().getFirst().params().get("typology_id")));
    }

    @Test
    void leavesExistingCourtyardCompoundAlone() {
        Component structure = new Component(
                "STRUCTURE", "main", new Vec3i(0, 0, 0),
                new Dimensions(36, 44, 10), List.of("typology:courtyard_compound"),
                Map.of("typology_id", "courtyard_compound", "component_id", "yard"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("Chinese_Vernacular_Siheyuan")
                .planMeta(Map.of("request_intent_text", "生成一座四合院"))
                .components(List.of(structure))
                .build();
        LlmPlan out = SiheyuanCourtyardMisrouteRepair.apply(plan);
        assertSame(plan, out);
    }

    private static Component mass(String id, int x, int z, int w, int d, int h) {
        return new Component(
                "MASS_MAIN", "main", new Vec3i(x, 0, z),
                new Dimensions(w, d, h), List.of(),
                Map.of("component_id", id, "anchor_mode", "min_corner"));
    }
}
