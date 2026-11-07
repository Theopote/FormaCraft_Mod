package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.ResolvedFootprint;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full repair-pipeline isolation: hex pavilion must not inherit sibling octagon intent. */
class PavilionHexOctagonIsolationTest {

    @Test
    void hexPavilionSurvivesOpenFrameRepairWhenPlanAlsoHasOctagon() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0),
                new Dimensions(16, 12, 8), List.of("main_hall"),
                Map.of("component_id", "hall_main", "building_role", "hall_main",
                        "shape", "rectangle", "anchor_mode", "min_corner"));
        Component hex = new Component(
                "MASS_MAIN", "east", new Vec3i(20, 0, 0),
                new Dimensions(9, 9, 5), List.of("六角亭", "pavilion"),
                Map.of("component_id", "east_hex_pavilion", "shape", "hexagon",
                        "building_role", "pavilion", "anchor_mode", "min_corner"));
        Component oct = new Component(
                "MASS_MAIN", "west", new Vec3i(-20, 0, 0),
                new Dimensions(9, 9, 5), List.of("八角亭", "pavilion"),
                Map.of("component_id", "west_oct_pavilion", "shape", "octagon",
                        "plan_type", "octagon", "building_role", "pavilion",
                        "anchor_mode", "min_corner"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .distinguishingFeatures(List.of("园林", "六角亭", "八角亭"))
                .components(List.of(hall, hex, oct))
                .build();

        LlmPlan afterHexNorm = FootprintShapeRemapRepair.apply(plan);
        LlmPlan afterOpen = PavilionOpenFrameRepair.apply(afterHexNorm);

        Component hexOut = byId(afterOpen, "east_hex_pavilion");
        Component octOut = byId(afterOpen, "west_oct_pavilion");
        Component hallOut = byId(afterOpen, "hall_main");

        assertEquals("hexagon", String.valueOf(hexOut.params().get("shape")));
        assertFalse("octagon".equalsIgnoreCase(String.valueOf(hexOut.params().getOrDefault("plan_type", ""))));
        assertEquals(6, ((Number) hexOut.params().get("sides")).intValue());

        assertEquals("octagon", String.valueOf(octOut.params().get("plan_type")));
        assertEquals(8, ((Number) octOut.params().get("sides")).intValue());

        assertNotEquals(Boolean.TRUE, hallOut.params().get("open"));

        ResolvedFootprint hexFp = ResolvedFootprint.resolve(hexOut, 9, 9);
        assertEquals(ResolvedFootprint.FootprintShape.HEXAGON, hexFp.shape());
        assertTrue(hexFp.contains(4, 4));
        assertFalse(hexFp.contains(0, 0));

        ResolvedFootprint octFp = ResolvedFootprint.resolve(octOut, 9, 9);
        assertEquals(ResolvedFootprint.FootprintShape.RECTANGLE, octFp.shape());
        assertEquals(ResolvedFootprint.PlanPattern.CUT_CORNERS, octFp.pattern());
    }

    @Test
    void chineseLiujiaotingTokenOpensAsPavilionWithoutOctagon() {
        Component ting = new Component(
                "MASS_MAIN", "p", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 5), List.of("六角亭"),
                mapOf("component_id", "garden_六角亭", "shape", "hexagon",
                        "anchor_mode", "min_corner"));
        LlmPlan repaired = PavilionOpenFrameRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(ting))
                .build());
        Component out = repaired.components().getFirst();
        assertEquals(Boolean.TRUE, out.params().get("open"));
        assertEquals("hexagon", String.valueOf(out.params().get("shape")));
        assertEquals(6, ((Number) out.params().get("sides")).intValue());
        assertNotEquals("none", String.valueOf(out.params().get("roof_type")).toLowerCase());
        assertEquals("pyramid", String.valueOf(out.params().get("preferred_roof_type")).toLowerCase());
    }

    private static Component byId(LlmPlan plan, String id) {
        return plan.components().stream()
                .filter(c -> id.equals(String.valueOf(c.params().get("component_id"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + id));
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
