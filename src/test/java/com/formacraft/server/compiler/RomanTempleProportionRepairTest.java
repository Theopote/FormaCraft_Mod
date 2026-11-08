package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RomanTempleProportionRepairTest {

    @Test
    void raisesCellaToMatchColumnHeight() {
        Map<String, Object> cellaParams = new HashMap<>();
        cellaParams.put("component_id", "temple_cella");
        cellaParams.put("shape", "circle");
        cellaParams.put("roof_type", "dome");
        cellaParams.put("open", true);
        cellaParams.put("wall_type", "none");

        List<Component> comps = new ArrayList<>();
        comps.add(new Component(
                "MASS_MAIN", "temple_slot",
                new Vec3i(0, 2, 0),
                new Dimensions(12, 12, 5),
                List.of("circular_cella"),
                cellaParams));
        comps.add(new Component(
                "MASS_ANCILLARY", "temple_slot",
                new Vec3i(9, 2, 0),
                new Dimensions(1, 1, 12),
                List.of("quartz_column", "circular_peristyle"),
                Map.of("component_id", "peristyle_column_01")));

        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("Classical_GrecoRoman")
                .components(comps)
                .planMeta(Map.of(
                        "request_intent_text", "生成一座半径为 9 的圆形罗马神殿。外围由 12 根石英柱环绕。"))
                .build();

        LlmPlan out = RomanTempleProportionRepair.apply(plan);
        Component cella = out.components().get(0);
        assertTrue(cella.dimensions().height() >= 12);
        assertEquals(false, cella.params().get("open"));
        assertTrue(cella.dimensions().width() >= 12);
    }

    @Test
    void doesNotSquareBaroqueMansionUnderGrecoRomanStyle() {
        Map<String, Object> mansionParams = new HashMap<>();
        mansionParams.put("component_id", "mansion_main");
        mansionParams.put("roof_type", "mansard");
        mansionParams.put("wall_block", "minecraft:cut_sandstone");

        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("Classical_GrecoRoman")
                .components(List.of(new Component(
                        "MASS_MAIN", "slot_mansion",
                        new Vec3i(-7, 0, -12),
                        new Dimensions(15, 25, 9),
                        List.of("baroque_mansion", "two_storey_shell"),
                        mansionParams)))
                .planMeta(Map.of(
                        "request_intent_text",
                        "生成一座 15x25 格的双层巴洛克式大宅。顶部为高耸的马萨折线形屋顶与带椭圆山花的老虎窗。"))
                .build();

        LlmPlan out = RomanTempleProportionRepair.apply(plan);
        Component mass = out.components().get(0);
        assertEquals(15, mass.dimensions().width());
        assertEquals(25, mass.dimensions().depth());
        assertEquals(9, mass.dimensions().height());
    }
}
