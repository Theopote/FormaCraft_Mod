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
        assertTrue(out.components().stream().anyMatch(c ->
                c.params() != null && "wall_beam_noise".equals(c.params().get("component_id"))),
                "authored balcony/terrace volumes must not be dropped by aspect ratio");
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
        assertNull(fixedPlate.params().get("roof_type"), "floor plates must not inherit mansard roof_type");
        assertNull(fixedPlate.params().get("preferred_roof_type"));

        Component fixedRoof = out.components().stream()
                .filter(c -> "ROOF".equals(c.componentType())).findFirst().orElseThrow();
        assertEquals(true, fixedRoof.params().get("roof_dormers"));
        Object specialtyHint = out.proportionHints() == null ? null : out.proportionHints().get("roof_specialty");
        assertTrue(specialtyHint == null
                || !String.valueOf(specialtyHint).toLowerCase().contains("dormer"),
                "global proportionHints must not broadcast mansard_dormer");
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
    void siblingMansardDoesNotRewriteUnlockedGableOrHipRoofs() {
        Component main = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 12), List.of("baroque"),
                Map.of("component_id", "main_house", "floor_count", 2, "floor_height", 5));
        Component mainRoof = new Component(
                "ROOF", "main", new Vec3i(0, 12, 0),
                new Dimensions(15, 25, 5), List.of(),
                Map.of("component_id", "main_roof", "host_id", "main_house", "roof_type", "mansard"));
        Component wing = new Component(
                "MASS_SECONDARY", "wing", new Vec3i(30, 0, 0),
                new Dimensions(10, 8, 6), List.of(),
                Map.of("component_id", "west_wing"));
        Component wingRoof = new Component(
                "ROOF", "wing", new Vec3i(30, 6, 0),
                new Dimensions(10, 8, 3), List.of(),
                Map.of("component_id", "west_roof", "host_id", "west_wing", "roof_type", "gable"));
        Component tower = new Component(
                "STRUCTURE", "tower", new Vec3i(50, 0, 0),
                new Dimensions(6, 6, 18), List.of("bell_tower", "gothic"),
                Map.of("component_id", "bell_tower"));
        Component towerRoof = new Component(
                "ROOF", "tower", new Vec3i(50, 18, 0),
                new Dimensions(6, 6, 4), List.of(),
                Map.of("component_id", "bell_roof", "host_id", "bell_tower", "roof_type", "hip"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("baroque")
                .components(List.of(main, mainRoof, wing, wingRoof, tower, towerRoof))
                .planMeta(Map.of("request_intent_text",
                        "巴洛克大宅用马萨屋顶，西侧附属住宅用人字屋顶，东侧哥特钟楼用四坡屋顶"))
                .build();

        LlmPlan out = BaroqueMansionStyleRepair.apply(plan);
        assertEquals("mansard", out.components().stream()
                .filter(c -> "main_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().params().get("roof_type"));
        assertEquals("gable", out.components().stream()
                .filter(c -> "west_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().params().get("roof_type"));
        assertEquals("hip", out.components().stream()
                .filter(c -> "bell_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().params().get("roof_type"));
    }

    @Test
    void dormersDoNotPropagateAcrossMansardHosts() {
        Component main = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 12), List.of("baroque"),
                Map.of("component_id", "main_house", "floor_count", 2, "floor_height", 5));
        Component mainRoof = new Component(
                "ROOF", "main", new Vec3i(0, 12, 0),
                new Dimensions(15, 25, 5), List.of(),
                Map.of("component_id", "main_roof", "host_id", "main_house",
                        "roof_type", "mansard", "roof_dormers", true));
        Component annex = new Component(
                "MASS_SECONDARY", "annex", new Vec3i(30, 0, 0),
                new Dimensions(10, 10, 8), List.of(),
                Map.of("component_id", "french_wing"));
        Component annexRoof = new Component(
                "ROOF", "annex", new Vec3i(30, 8, 0),
                new Dimensions(10, 10, 4), List.of(),
                Map.of("component_id", "annex_roof", "host_id", "french_wing", "roof_type", "mansard"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("baroque")
                .components(List.of(main, mainRoof, annex, annexRoof))
                .planMeta(Map.of("request_intent_text",
                        "巴洛克主楼马萨屋顶带老虎窗，法式附属楼也用马萨屋顶但不要老虎窗"))
                .build();

        LlmPlan out = BaroqueMansionStyleRepair.apply(plan);
        assertEquals(true, out.components().stream()
                .filter(c -> "main_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().params().get("roof_dormers"));
        Component annexOut = out.components().stream()
                .filter(c -> "annex_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertNotEquals(true, annexOut.params().get("roof_dormers"));
        assertFalse(String.valueOf(annexOut.params().getOrDefault("roof_specialty", ""))
                .toLowerCase().contains("dormer"));
    }

    @Test
    void baroqueWithGateAndGothicTowerStillRepairsPrimaryMansard() {
        Component main = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 8), List.of("baroque"),
                Map.of("component_id", "main_house", "floor_count", 2, "floor_height", 2));
        Component roof = new Component(
                "ROOF", "main", new Vec3i(0, 8, 0),
                new Dimensions(15, 25, 5), List.of(),
                Map.of("component_id", "main_roof", "host_id", "main_house", "roof_type", "mansard"));
        Component gate = new Component(
                "STRUCTURE", "gate", new Vec3i(-20, 0, 0),
                new Dimensions(8, 4, 10), List.of("gate"),
                Map.of("component_id", "entry_gate"));
        Component bell = new Component(
                "STRUCTURE", "tower", new Vec3i(40, 0, 0),
                new Dimensions(6, 6, 20), List.of("gothic", "bell_tower"),
                Map.of("component_id", "gothic_bell"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("baroque")
                .components(List.of(main, roof, gate, bell))
                .planMeta(Map.of("request_intent_text",
                        "建造一座巴洛克大宅，前方独立门楼，旁侧哥特钟楼"))
                .build();

        LlmPlan out = BaroqueMansionStyleRepair.apply(plan);
        Component fixedMass = out.components().stream()
                .filter(c -> "main_house".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertTrue(((Number) fixedMass.params().get("floor_height")).intValue() >= 4);
        assertEquals("STRUCTURE", out.components().stream()
                .filter(c -> "entry_gate".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().componentType());
        assertEquals("STRUCTURE", out.components().stream()
                .filter(c -> "gothic_bell".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().componentType());
    }

    @Test
    void frenchMansardAnnexDoesNotStealBaroqueTargetFromHipMain() {
        Component main = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 8), List.of("baroque"),
                Map.of("component_id", "main_house", "floor_count", 2, "floor_height", 2));
        Component mainRoof = new Component(
                "ROOF", "main", new Vec3i(0, 8, 0),
                new Dimensions(15, 25, 4), List.of(),
                Map.of("component_id", "main_roof", "host_id", "main_house", "roof_type", "hip"));
        Component french = new Component(
                "MASS_SECONDARY", "wing", new Vec3i(30, 0, 0),
                new Dimensions(10, 10, 8), List.of(),
                Map.of("component_id", "french_wing", "floor_count", 2, "floor_height", 2));
        Component frenchRoof = new Component(
                "ROOF", "wing", new Vec3i(30, 8, 0),
                new Dimensions(10, 10, 4), List.of(),
                Map.of("component_id", "french_roof", "host_id", "french_wing", "roof_type", "mansard"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("baroque")
                .components(List.of(main, mainRoof, french, frenchRoof))
                .planMeta(Map.of("request_intent_text",
                        "主楼是一栋巴洛克大宅采用四坡屋顶；东侧另外建一栋法式马萨屋顶住宅"))
                .build();

        LlmPlan out = BaroqueMansionStyleRepair.apply(plan);
        Component fixedMain = out.components().stream()
                .filter(c -> "main_house".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertTrue(((Number) fixedMain.params().get("floor_height")).intValue() >= 4);
        assertEquals("base_plinth", fixedMain.params().get("facade_profile"));
        Component fixedFrench = out.components().stream()
                .filter(c -> "french_wing".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(2, ((Number) fixedFrench.params().get("floor_height")).intValue());
        assertNull(fixedFrench.params().get("facade_profile"));
        assertEquals("mansard", out.components().stream()
                .filter(c -> "french_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().params().get("roof_type"));
        assertEquals("hip", out.components().stream()
                .filter(c -> "main_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().params().get("roof_type"));
    }

    @Test
    void facadePilasterOnBaroqueHostIsNotDroppedBySkinnyAspect() {
        Component main = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 12), List.of("baroque"),
                Map.of("component_id", "main_house", "floor_count", 2, "floor_height", 5));
        Component pilaster = new Component(
                "DECOR_DETAIL", "main", new Vec3i(0, 1, 4),
                new Dimensions(2, 2, 10), List.of("pilaster"),
                Map.of("component_id", "west_facade_pilaster", "host_id", "main_house"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("baroque")
                .components(List.of(main, pilaster))
                .planMeta(Map.of("request_intent_text", "巴洛克大宅立面壁柱"))
                .build();

        LlmPlan out = BaroqueMansionStyleRepair.apply(plan);
        assertTrue(out.components().stream().anyMatch(c ->
                "west_facade_pilaster".equals(String.valueOf(c.params().get("component_id")))));
    }

    @Test
    void firstMassMainIsNotAssumedBaroqueTargetForMansardCorpus() {
        Component ordinary = new Component(
                "MASS_MAIN", "west", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of(),
                Map.of("component_id", "west_house", "floor_count", 2, "floor_height", 4));
        Component ordinaryRoof = new Component(
                "ROOF", "west", new Vec3i(0, 8, 0),
                new Dimensions(12, 10, 3), List.of(),
                Map.of("component_id", "west_roof", "host_id", "west_house", "roof_type", "gable"));
        Component baroque = new Component(
                "MASS_MAIN", "east", new Vec3i(40, 0, 0),
                new Dimensions(15, 25, 12), List.of("baroque"),
                Map.of("component_id", "east_mansion", "floor_count", 2, "floor_height", 5));
        // Blank roof_type: corpus mansard may fill the unique baroque host only — never west gable.
        Component baroqueRoof = new Component(
                "ROOF", "east", new Vec3i(40, 12, 0),
                new Dimensions(15, 25, 5), List.of(),
                Map.of("component_id", "east_roof", "host_id", "east_mansion"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("baroque")
                .components(List.of(ordinary, ordinaryRoof, baroque, baroqueRoof))
                .planMeta(Map.of("request_intent_text",
                        "西侧普通住宅用人字屋顶，东侧巴洛克大宅采用马萨屋顶"))
                .build();

        LlmPlan out = BaroqueMansionStyleRepair.apply(plan);
        assertEquals("gable", out.components().stream()
                .filter(c -> "west_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().params().get("roof_type"));
        assertEquals("mansard", out.components().stream()
                .filter(c -> "east_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().params().get("roof_type"));
    }

    @Test
    void dormersSurviveRoofGrammarOnlyOnAuthorizedHost() {
        Component main = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 12), List.of("baroque"),
                Map.of("component_id", "main_house", "floor_count", 2, "floor_height", 5));
        Component mainRoof = new Component(
                "ROOF", "main", new Vec3i(0, 12, 0),
                new Dimensions(15, 25, 5), List.of(),
                Map.of("component_id", "main_roof", "host_id", "main_house",
                        "roof_type", "mansard", "roof_dormers", true));
        Component annex = new Component(
                "MASS_SECONDARY", "annex", new Vec3i(30, 0, 0),
                new Dimensions(10, 10, 8), List.of(),
                Map.of("component_id", "french_wing"));
        Component annexRoof = new Component(
                "ROOF", "annex", new Vec3i(30, 8, 0),
                new Dimensions(10, 10, 4), List.of(),
                Map.of("component_id", "annex_roof", "host_id", "french_wing", "roof_type", "mansard"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("baroque")
                .components(List.of(main, mainRoof, annex, annexRoof))
                .planMeta(Map.of("request_intent_text",
                        "巴洛克主楼马萨屋顶带老虎窗，法式附属楼也用马萨屋顶但不要老虎窗"))
                .build();

        LlmPlan afterBaroque = BaroqueMansionStyleRepair.apply(plan);
        Component annexAfterRepair = afterBaroque.components().stream()
                .filter(c -> "annex_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        Component annexAfterGrammar = com.formacraft.common.proportion.RoofGrammarResolver.apply(
                afterBaroque, annexAfterRepair);
        assertNotEquals(true, annexAfterGrammar.params().get("roof_dormers"));
        assertFalse(String.valueOf(annexAfterGrammar.params().getOrDefault("roof_specialty", ""))
                .toLowerCase().contains("dormer"));
        Component mainAfterGrammar = com.formacraft.common.proportion.RoofGrammarResolver.apply(
                afterBaroque,
                afterBaroque.components().stream()
                        .filter(c -> "main_roof".equals(String.valueOf(c.params().get("component_id"))))
                        .findFirst().orElseThrow());
        assertEquals(true, mainAfterGrammar.params().get("roof_dormers"));
    }

    @Test
    void stadiumAndOrdinaryWingStayUntouchedAfterBaroqueEnrich() {
        Component main = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 8), List.of("baroque"),
                Map.of("component_id", "main_house", "floor_count", 2, "floor_height", 2));
        Component wing = new Component(
                "MASS_SECONDARY", "wing", new Vec3i(30, 0, 0),
                new Dimensions(10, 8, 6), List.of(),
                Map.of("component_id", "west_wing", "floor_count", 2, "floor_height", 2));
        Component arena = new Component(
                "STRUCTURE", "arena", new Vec3i(60, 0, 0),
                new Dimensions(40, 60, 20),
                List.of("typology:stadium_bowl"),
                Map.of("component_id", "east_stadium", "typology_id", "stadium_bowl",
                        "floor_height", 3));
        Component arenaColumn = new Component(
                "DECOR_DETAIL", "arena", new Vec3i(62, 2, 2),
                new Dimensions(2, 2, 10), List.of("column"),
                Map.of("component_id", "east_stadium_column", "host_id", "east_stadium"));
        Map<String, Object> hints = new HashMap<>();
        hints.put("typology", "stadium_bowl");
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("baroque")
                .components(List.of(main, wing, arena, arenaColumn))
                .proportionHints(hints)
                .planMeta(Map.of("request_intent_text",
                        "建造一座巴洛克大宅、西侧普通附属住宅，东侧独立体育场"))
                .build();

        LlmPlan out = BaroqueMansionStyleRepair.apply(plan);
        Component fixedMain = out.components().stream()
                .filter(c -> "main_house".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertTrue(((Number) fixedMain.params().get("floor_height")).intValue() >= 4);
        Component fixedWing = out.components().stream()
                .filter(c -> "west_wing".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(2, ((Number) fixedWing.params().get("floor_height")).intValue());
        assertNull(fixedWing.params().get("facade_profile"));
        Component fixedArena = out.components().stream()
                .filter(c -> "east_stadium".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(3, ((Number) fixedArena.params().get("floor_height")).intValue());
        assertEquals("stadium_bowl", fixedArena.params().get("typology_id"));
        assertNull(fixedArena.params().get("facade_profile"));
        assertTrue(fixedArena.features() == null
                || fixedArena.features().stream().noneMatch(f -> f != null && f.contains("base_plinth")));
        assertTrue(out.components().stream().anyMatch(c ->
                "east_stadium_column".equals(String.valueOf(c.params().get("component_id")))));
        assertEquals("stadium_bowl", out.proportionHints().get("typology"));
    }

    @Test
    void stadiumPavingAndGateEntranceAreNotRewrittenAsMainPlaza() {
        Component main = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 12), List.of("baroque"),
                Map.of("component_id", "main_house", "floor_count", 2, "floor_height", 5));
        Component stadiumPaving = new Component(
                "PAVING", "arena", new Vec3i(60, 0, 0),
                new Dimensions(20, 20, 1), List.of(),
                Map.of("component_id", "arena_plaza", "host_id", "east_stadium"));
        Component gateEntrance = new Component(
                "ENTRANCE", "gate", new Vec3i(-12, 0, 0),
                new Dimensions(3, 1, 4), List.of("gate"),
                Map.of("component_id", "gate_door", "host_id", "entry_gate"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("baroque")
                .components(List.of(main, stadiumPaving, gateEntrance))
                .planMeta(Map.of("request_intent_text",
                        "建造一座巴洛克大宅，前方独立门楼，东侧体育场铺地"))
                .build();

        LlmPlan out = BaroqueMansionStyleRepair.apply(plan);
        Component paving = out.components().stream()
                .filter(c -> "arena_plaza".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertNull(paving.params().get("role"));
        assertNull(paving.params().get("entrance_id"));
        Component gateDoor = out.components().stream()
                .filter(c -> "gate_door".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(0, gateDoor.relativePosition().y());
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
