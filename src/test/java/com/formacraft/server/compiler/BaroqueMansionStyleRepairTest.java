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

class BaroqueMansionStyleRepairTest {

    @Test
    void dropsOversizedRoofDormerDecorCapsChimneysAndRaisesFloorHeight() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 6), List.of(),
                Map.of("component_id", "main_house", "floor_count", 2, "floor_height", 2));
        Component plate = new Component(
                "MASS_SECONDARY", "main", new Vec3i(0, 3, 0),
                new Dimensions(15, 25, 6), List.of("floor_plate"),
                Map.of("component_id", "floor_plate_1", "extrude_mode", "plate", "host_id", "main_house"));
        Component roof = new Component(
                "ROOF", "main", new Vec3i(0, 6, 0),
                new Dimensions(15, 25, 7), List.of(),
                Map.of("component_id", "mansard_roof", "roof_type", "mansard", "host_id", "main_house"));
        Component decorDormers = new Component(
                "DECOR_DETAIL", "main", new Vec3i(0, 8, 0),
                new Dimensions(15, 25, 4), List.of("oval pediment dormers"),
                Map.of("component_id", "oval_pediment_dormers", "host_id", "mansard_roof"));
        Component chimney1 = new Component(
                "CHIMNEY", "main", new Vec3i(2, 10, 4),
                new Dimensions(2, 2, 5), List.of(),
                Map.of("component_id", "chimney_west", "host_id", "mansard_roof"));
        Component chimney2 = new Component(
                "CHIMNEY", "main", new Vec3i(12, 10, 20),
                new Dimensions(2, 2, 5), List.of(),
                Map.of("component_id", "chimney_east", "host_id", "mansard_roof"));
        Component beam = new Component(
                "BALCONY", "main", new Vec3i(15, 4, 8),
                new Dimensions(6, 1, 1), List.of(),
                Map.of("component_id", "wall_beam_noise", "host_id", "main_house"));
        Component atticSpike = new Component(
                "DECOR_DETAIL", "main", new Vec3i(2, 12, 4),
                new Dimensions(2, 2, 5), List.of("attic column"),
                Map.of("component_id", "pediment_column", "host_id", "mansard_roof"));
        Map<String, Object> meta = new HashMap<>();
        meta.put("request_intent_text", "生成一座 15x25 格的双层巴洛克式大宅。顶部为高耸的马萨折线屋顶，带有椭圆山花老虎窗");
        meta.put("request_intent", Map.of(
                "original_text", "生成一座 15x25 格的双层巴洛克式大宅。顶部为高耸的马萨折线屋顶，带有椭圆山花老虎窗"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("baroque")
                .components(List.of(mass, plate, roof, decorDormers, chimney1, chimney2, beam, atticSpike))
                .planMeta(meta)
                .playerFidelityNoticeZh("组合式生成「双层巴洛克式大宅」")
                .build();

        LlmPlan out = BaroqueMansionStyleRepair.apply(plan);
        assertTrue(out.components().stream().noneMatch(c ->
                c.params() != null && "oval_pediment_dormers".equals(c.params().get("component_id"))),
                "oversized roof decor rings must be dropped");
        assertTrue(out.components().stream().noneMatch(c ->
                c.params() != null && "wall_beam_noise".equals(c.params().get("component_id"))));
        assertTrue(out.components().stream().noneMatch(c ->
                c.params() != null && "pediment_column".equals(c.params().get("component_id"))));
        assertEquals(1, out.components().stream().filter(c -> "CHIMNEY".equals(c.componentType())).count());

        Component fixedMass = out.components().stream()
                .filter(c -> "MASS_MAIN".equals(c.componentType())).findFirst().orElseThrow();
        assertEquals(5, ((Number) fixedMass.params().get("floor_height")).intValue());
        assertEquals(6, ((Number) fixedMass.params().get("ground_clear_height")).intValue());
        assertTrue(fixedMass.dimensions().height() >= 11);

        Component fixedPlate = out.components().stream()
                .filter(c -> c.params() != null && "floor_plate_1".equals(c.params().get("component_id")))
                .findFirst().orElseThrow();
        assertEquals(1, fixedPlate.dimensions().height());

        Component fixedRoof = out.components().stream()
                .filter(c -> "ROOF".equals(c.componentType())).findFirst().orElseThrow();
        assertEquals(true, fixedRoof.params().get("roof_dormers"));
    }
}
