package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BaroqueMansionStyleRepairTest {

    @BeforeEach
    void clearBefore() {
        AssemblyCompileDiagnostics.clear();
    }

    @AfterEach
    void clearAfter() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void dropsOversizedRoofDormerDecorKeepsSymmetricChimneysAndRaisesFloorHeight() {
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
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
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
        assertEquals(2, out.components().stream().filter(c -> "CHIMNEY".equals(c.componentType())).count());

        Component fixedMass = out.components().stream()
                .filter(c -> "MASS_MAIN".equals(c.componentType())).findFirst().orElseThrow();
        assertTrue(((Number) fixedMass.params().get("floor_height")).intValue() >= 4);
        assertEquals(15, fixedMass.dimensions().width());
        assertEquals(25, fixedMass.dimensions().depth());
        assertTrue(fixedMass.dimensions().height() >= 8);

        Component fixedPlate = out.components().stream()
                .filter(c -> c.params() != null && "floor_plate_1".equals(c.params().get("component_id")))
                .findFirst().orElseThrow();
        assertEquals(1, fixedPlate.dimensions().height());

        Component fixedRoof = out.components().stream()
                .filter(c -> "ROOF".equals(c.componentType())).findFirst().orElseThrow();
        assertEquals(true, fixedRoof.params().get("roof_dormers"));
    }

    @Test
    void lockedHipRoofIsNotForcedToMansardAndAnnexGableKeepsNoDormers() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 12), List.of("baroque"),
                Map.of("component_id", "main_house", "floor_count", 2, "floor_height", 5));
        Map<String, Object> hipParams = new HashMap<>();
        hipParams.put("component_id", "main_roof");
        hipParams.put("host_id", "main_house");
        hipParams.put("roof_type", "hip");
        hipParams.put("design_sources", Map.of("roof_type", "USER_EXPLICIT"));
        Component hipRoof = new Component(
                "ROOF", "main", new Vec3i(0, 12, 0),
                new Dimensions(15, 25, 5), List.of(), hipParams);
        Component annex = new Component(
                "MASS_SECONDARY", "annex", new Vec3i(30, 0, 0),
                new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "side_hall"));
        Component annexRoof = new Component(
                "ROOF", "annex", new Vec3i(30, 6, 0),
                new Dimensions(8, 8, 3), List.of(),
                Map.of("component_id", "side_roof", "host_id", "side_hall", "roof_type", "xieshan"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("baroque")
                .components(List.of(mass, hipRoof, annex, annexRoof))
                .planMeta(Map.of("request_intent_text",
                        "建造一座巴洛克风格大宅，屋顶使用四坡屋顶，不要老虎窗；旁附歇山配殿"))
                .build();

        LlmPlan out = BaroqueMansionStyleRepair.apply(plan);
        assertEquals("hip", out.components().stream()
                .filter(c -> "main_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().params().get("roof_type"));
        Component sideRoof = out.components().stream()
                .filter(c -> "side_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("xieshan", sideRoof.params().get("roof_type"));
        assertNotEquals(true, sideRoof.params().get("roof_dormers"));
        assertFalse(String.valueOf(sideRoof.params().getOrDefault("roof_specialty", ""))
                .toLowerCase().contains("dormer"));
    }

    @Test
    void lockedStoreyHeightIsNotRaised() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "main_house");
        params.put("floor_count", 2);
        params.put("floor_height", 2);
        params.put("storey_height", 2);
        params.put("design_sources", Map.of("storey_height", "USER_EXPLICIT", "floor_height", "USER_EXPLICIT"));
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(12, 16, 6), List.of("baroque"), params);
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("baroque")
                .components(List.of(mass))
                .planMeta(Map.of("request_intent_text", "巴洛克大宅，层高两格"))
                .build();

        LlmPlan out = BaroqueMansionStyleRepair.apply(plan);
        Component fixed = out.components().getFirst();
        assertEquals(2, ((Number) fixed.params().get("floor_height")).intValue());
        assertEquals(2, ((Number) fixed.params().get("storey_height")).intValue());
    }
}
