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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StadiumMisrouteRepairTest {

    @BeforeEach
    void clearBefore() {
        AssemblyCompileDiagnostics.clear();
    }

    @AfterEach
    void clearAfter() {
        AssemblyCompileDiagnostics.clear();
    }

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
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Modern_Stadium_Elliptical")
                .components(List.of(structure))
                .planMeta(Map.of(
                        "request_intent_text",
                        "生成一座 15x25 格的双层巴洛克式大宅。顶部为高耸的马萨折线形屋顶与带椭圆山花的老虎窗。"))
                .build();

        LlmPlan out = StadiumMisrouteRepair.apply(plan);
        Component c = out.components().getFirst();
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
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(structure))
                .planMeta(Map.of("request_intent_text", "生成一座椭圆体育场，带看台"))
                .build();

        LlmPlan out = StadiumMisrouteRepair.apply(plan);
        assertEquals("STRUCTURE", out.components().get(0).componentType());
        assertEquals("stadium_bowl", out.components().get(0).params().get("typology_id"));
    }

    @Test
    void foreignAnnexRoofIsNotForcedToMansard() {
        Map<String, Object> mansionParams = new HashMap<>();
        mansionParams.put("component_id", "mansion");
        mansionParams.put("typology_id", "stadium_bowl");
        mansionParams.put("elliptical", true);
        Component mansion = new Component(
                "STRUCTURE", "main",
                new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 18),
                List.of("typology:stadium_bowl", "ribbon_glazing"),
                mansionParams);
        Component annex = new Component(
                "MASS_SECONDARY", "annex",
                new Vec3i(30, 0, 0),
                new Dimensions(8, 8, 6),
                List.of("garden_pavilion"),
                Map.of("component_id", "pavilion", "roof_type", "gable"));
        Component annexRoof = new Component(
                "ROOF", "annex",
                new Vec3i(30, 6, 0),
                new Dimensions(8, 8, 3),
                List.of(),
                Map.of("component_id", "pav_roof", "host_id", "pavilion", "roof_type", "gable"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Modern_Stadium_Elliptical")
                .components(List.of(mansion, annex, annexRoof))
                .planMeta(Map.of(
                        "request_intent_text",
                        "巴洛克大宅带马萨屋顶，旁附一座双坡园亭"))
                .build();

        LlmPlan out = StadiumMisrouteRepair.apply(plan);
        Component outAnnexRoof = out.components().stream()
                .filter(c -> "pav_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("gable", outAnnexRoof.params().get("roof_type"));
        assertEquals("MASS_SECONDARY", out.components().stream()
                .filter(c -> "pavilion".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().componentType());
        assertTrue(out.components().stream().anyMatch(c ->
                c.componentType() != null && c.componentType().toUpperCase().startsWith("ROOF")
                        && "mansard".equals(String.valueOf(c.params().get("roof_type")))));
    }

    @Test
    void annexWithMaterialHintsKeepsGableRoof() {
        Map<String, Object> mansionParams = new HashMap<>();
        mansionParams.put("component_id", "mansion");
        mansionParams.put("typology_id", "stadium_bowl");
        mansionParams.put("elliptical", true);
        Component mansion = new Component(
                "STRUCTURE", "main",
                new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 18),
                List.of("typology:stadium_bowl"),
                mansionParams);
        Map<String, Object> pavilionParams = new HashMap<>();
        pavilionParams.put("component_id", "pavilion");
        pavilionParams.put("roof_type", "gable");
        pavilionParams.put("material_hints", Map.of(
                "primary", "minecraft:oak_planks",
                "accent", "minecraft:stone_bricks"));
        Component annex = new Component(
                "MASS_SECONDARY", "annex",
                new Vec3i(30, 0, 0),
                new Dimensions(8, 8, 6),
                List.of("garden_pavilion"),
                pavilionParams);
        Component annexRoof = new Component(
                "ROOF", "annex",
                new Vec3i(30, 6, 0),
                new Dimensions(8, 8, 3),
                List.of(),
                Map.of("component_id", "pav_roof", "host_id", "pavilion", "roof_type", "gable"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Modern_Stadium_Elliptical")
                .components(List.of(mansion, annex, annexRoof))
                .planMeta(Map.of("request_intent_text", "巴洛克大宅带马萨屋顶，旁附一座双坡园亭"))
                .build();

        LlmPlan out = StadiumMisrouteRepair.apply(plan);
        Component outPav = out.components().stream()
                .filter(c -> "pavilion".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertNotEquals(Boolean.TRUE, outPav.params().get("stadium_misroute_demoted"));
        assertTrue(outPav.params().get("material_hints") instanceof Map<?, ?>);
        assertEquals("gable", out.components().stream()
                .filter(c -> "pav_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().params().get("roof_type"));
    }

    @Test
    void foreignBellTowerStructureIsNotDemoted() {
        Map<String, Object> mansionParams = new HashMap<>();
        mansionParams.put("component_id", "mansion");
        mansionParams.put("typology_id", "stadium_bowl");
        Component mansion = new Component(
                "STRUCTURE", "main",
                new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 18),
                List.of("typology:stadium_bowl"),
                mansionParams);
        Component bell = new Component(
                "STRUCTURE", "tower",
                new Vec3i(40, 0, 0),
                new Dimensions(6, 6, 20),
                List.of("bell_tower"),
                Map.of("component_id", "bell_tower", "typology_id", "gothic_bell_tower"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Modern_Stadium_Elliptical")
                .components(List.of(mansion, bell))
                .planMeta(Map.of("request_intent_text", "巴洛克大宅带马萨屋顶，旁附独立钟楼"))
                .build();

        LlmPlan out = StadiumMisrouteRepair.apply(plan);
        Component outBell = out.components().stream()
                .filter(c -> "bell_tower".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("STRUCTURE", outBell.componentType());
        assertEquals("gothic_bell_tower", outBell.params().get("typology_id"));
        assertEquals("MASS_MAIN", out.components().stream()
                .filter(c -> "mansion".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().componentType());
    }

    @Test
    void userExplicitRoofTypeIsNotRewrittenToMansard() {
        Map<String, Object> mansionParams = new HashMap<>();
        mansionParams.put("component_id", "mansion");
        mansionParams.put("typology_id", "stadium_bowl");
        Component mansion = new Component(
                "STRUCTURE", "main",
                new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 18),
                List.of("typology:stadium_bowl"),
                mansionParams);
        Map<String, Object> roofParams = new HashMap<>();
        roofParams.put("component_id", "mansion_roof");
        roofParams.put("host_id", "mansion");
        roofParams.put("roof_type", "hip");
        roofParams.put("design_sources", Map.of("roof_type", "USER_EXPLICIT"));
        Component roof = new Component(
                "ROOF", "main",
                new Vec3i(0, 18, 0),
                new Dimensions(15, 25, 5),
                List.of(),
                roofParams);
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Modern_Stadium_Elliptical")
                .components(List.of(mansion, roof))
                .planMeta(Map.of("request_intent_text", "巴洛克大宅带马萨屋顶"))
                .build();

        LlmPlan out = StadiumMisrouteRepair.apply(plan);
        Component outRoof = out.components().stream()
                .filter(c -> "mansion_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("hip", outRoof.params().get("roof_type"));
        assertNull(outRoof.params().get("roof_specialty"));
        assertNull(outRoof.params().get("roof_dormers"));
    }

    @Test
    void annexVoidRatioDoesNotEnterDemotedHostsOrLoseValue() {
        Map<String, Object> mansionParams = new HashMap<>();
        mansionParams.put("component_id", "mansion");
        mansionParams.put("typology_id", "stadium_bowl");
        mansionParams.put("meshStructure", true);
        Component mansion = new Component(
                "STRUCTURE", "main",
                new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 18),
                List.of("typology:stadium_bowl"),
                mansionParams);
        Map<String, Object> annexParams = new HashMap<>();
        annexParams.put("component_id", "side_hall");
        annexParams.put("roof_type", "gable");
        annexParams.put("void_ratio", 0.15);
        annexParams.put("tier_count", 1);
        Component annex = new Component(
                "MASS_SECONDARY", "annex",
                new Vec3i(30, 0, 0),
                new Dimensions(10, 8, 6),
                List.of(),
                annexParams);
        Component annexRoof = new Component(
                "ROOF", "annex",
                new Vec3i(30, 6, 0),
                new Dimensions(10, 8, 3),
                List.of(),
                Map.of("component_id", "side_roof", "host_id", "side_hall", "roof_type", "gable"));
        Component bell = new Component(
                "STRUCTURE", "tower",
                new Vec3i(50, 0, 0),
                new Dimensions(6, 6, 18),
                List.of("bell_tower"),
                Map.of("component_id", "bell_tower", "typology_id", "gothic_bell_tower"));
        Map<String, Object> hipRoofParams = new HashMap<>();
        hipRoofParams.put("component_id", "bell_roof");
        hipRoofParams.put("host_id", "bell_tower");
        hipRoofParams.put("roof_type", "hip");
        hipRoofParams.put("design_sources", Map.of("roof_type", "USER_EXPLICIT"));
        Component bellRoof = new Component(
                "ROOF", "tower",
                new Vec3i(50, 18, 0),
                new Dimensions(6, 6, 3),
                List.of(),
                hipRoofParams);
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Modern_Stadium_Elliptical")
                .components(List.of(mansion, annex, annexRoof, bell, bellRoof))
                .planMeta(Map.of("request_intent_text", "巴洛克大宅带马萨屋顶，旁附配殿与钟楼"))
                .build();

        LlmPlan out = StadiumMisrouteRepair.apply(plan);
        Component outAnnex = out.components().stream()
                .filter(c -> "side_hall".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(0.15, ((Number) outAnnex.params().get("void_ratio")).doubleValue(), 1e-6);
        assertEquals(1, ((Number) outAnnex.params().get("tier_count")).intValue());
        assertNotEquals(Boolean.TRUE, outAnnex.params().get("stadium_misroute_demoted"));
        assertEquals("gable", out.components().stream()
                .filter(c -> "side_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().params().get("roof_type"));
        Component outBell = out.components().stream()
                .filter(c -> "bell_tower".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("STRUCTURE", outBell.componentType());
        Component outBellRoof = out.components().stream()
                .filter(c -> "bell_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("hip", outBellRoof.params().get("roof_type"));
        assertNull(outBellRoof.params().get("roof_specialty"));
        assertEquals("MASS_MAIN", out.components().stream()
                .filter(c -> "mansion".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().componentType());
    }

    @Test
    void unboundDistantRoofIsNotAssumedPrimary() {
        Map<String, Object> mansionParams = new HashMap<>();
        mansionParams.put("component_id", "mansion");
        mansionParams.put("typology_id", "stadium_bowl");
        Component mansion = new Component(
                "STRUCTURE", "main",
                new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 18),
                List.of("typology:stadium_bowl"),
                mansionParams);
        Component sideHall = new Component(
                "MASS_SECONDARY", "side",
                new Vec3i(40, 0, 0),
                new Dimensions(10, 8, 6),
                List.of(),
                Map.of("component_id", "side_hall", "roof_type", "gable"));
        // Unbound roof spatially over the side hall — must not become mansard via primary demotion.
        Component unboundRoof = new Component(
                "ROOF", "side",
                new Vec3i(40, 6, 0),
                new Dimensions(10, 8, 3),
                List.of(),
                Map.of("component_id", "side_roof", "roof_type", "gable"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Modern_Stadium_Elliptical")
                .components(List.of(mansion, sideHall, unboundRoof))
                .planMeta(Map.of("request_intent_text", "巴洛克大宅带马萨屋顶，旁附配殿"))
                .build();

        LlmPlan out = StadiumMisrouteRepair.apply(plan);
        assertEquals("gable", out.components().stream()
                .filter(c -> "side_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().params().get("roof_type"));
    }

    @Test
    void templeTierStepAndTulouMeshAreNotStadiumEvidence() {
        Map<String, Object> mansionParams = new HashMap<>();
        mansionParams.put("component_id", "mansion");
        mansionParams.put("typology_id", "stadium_bowl");
        Component mansion = new Component(
                "STRUCTURE", "main",
                new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 18),
                List.of("typology:stadium_bowl"),
                mansionParams);
        Map<String, Object> templeParams = new HashMap<>();
        templeParams.put("component_id", "altar_hall");
        templeParams.put("typology_id", "radial_terrace_hall");
        templeParams.put("tierStep", 3);
        Component temple = new Component(
                "STRUCTURE", "temple",
                new Vec3i(40, 0, 0),
                new Dimensions(24, 24, 16),
                List.of("typology:radial_terrace_hall"),
                templeParams);
        Map<String, Object> tulouParams = new HashMap<>();
        tulouParams.put("component_id", "tulou");
        tulouParams.put("module_id", "tulou");
        tulouParams.put("meshStructure", true);
        Component tulou = new Component(
                "MODULE", "tulou",
                new Vec3i(80, 0, 0),
                new Dimensions(30, 30, 20),
                List.of("module:tulou"),
                tulouParams);
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Modern_Stadium_Elliptical")
                .components(List.of(mansion, temple, tulou))
                .planMeta(Map.of("request_intent_text", "巴洛克大宅带马萨屋顶，旁附天坛式祭坛与土楼"))
                .build();

        LlmPlan out = StadiumMisrouteRepair.apply(plan);
        Component outTemple = out.components().stream()
                .filter(c -> "altar_hall".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("STRUCTURE", outTemple.componentType());
        assertEquals("radial_terrace_hall", outTemple.params().get("typology_id"));
        assertEquals(3, ((Number) outTemple.params().get("tierStep")).intValue());
        assertNotEquals(Boolean.TRUE, outTemple.params().get("stadium_misroute_demoted"));
        Component outTulou = out.components().stream()
                .filter(c -> "tulou".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("MODULE", outTulou.componentType());
        assertEquals("tulou", outTulou.params().get("module_id"));
        assertEquals(true, outTulou.params().get("meshStructure"));
        assertEquals("MASS_MAIN", out.components().stream()
                .filter(c -> "mansion".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().componentType());
    }

    @Test
    void styleOnlyPoisonDoesNotDemoteLeadingBellTower() {
        Component bell = new Component(
                "STRUCTURE", "tower",
                new Vec3i(0, 0, 0),
                new Dimensions(6, 6, 20),
                List.of("bell_tower"),
                Map.of("component_id", "bell_tower", "typology_id", "gothic_bell_tower"));
        Component mansion = new Component(
                "MASS_MAIN", "main",
                new Vec3i(20, 0, 0),
                new Dimensions(15, 25, 18),
                List.of("baroque"),
                Map.of("component_id", "mansion", "roof_type", "flat"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Modern_Stadium_Elliptical")
                .components(List.of(bell, mansion))
                .planMeta(Map.of("request_intent_text", "巴洛克大宅，屋顶使用平屋顶，不要老虎窗"))
                .build();

        LlmPlan out = StadiumMisrouteRepair.apply(plan);
        assertEquals("STRUCTURE", out.components().stream()
                .filter(c -> "bell_tower".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().componentType());
        assertFalse("Modern_Stadium_Elliptical".equalsIgnoreCase(out.styleProfile()));
        assertTrue(out.components().stream().noneMatch(c ->
                c.componentType() != null && c.componentType().toUpperCase().startsWith("ROOF")
                        && "mansard".equals(String.valueOf(c.params().get("roof_type")))));
        assertNotEquals(Boolean.TRUE, out.components().stream()
                .filter(c -> "mansion".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().params().get("stadium_misroute_demoted"));
    }

    @Test
    void negatedStadiumCueStillDemotesMisroutedMansion() {
        Component mansion = new Component(
                "STRUCTURE", "main",
                new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 18),
                List.of("typology:stadium_bowl"),
                Map.of("component_id", "mansion", "typology_id", "stadium_bowl"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Modern_Stadium_Elliptical")
                .components(List.of(mansion))
                .planMeta(Map.of("request_intent_text", "建造一座巴洛克大宅，不要做成体育场"))
                .build();

        LlmPlan out = StadiumMisrouteRepair.apply(plan);
        assertEquals("MASS_MAIN", out.components().getFirst().componentType());
        assertFalse(String.valueOf(out.components().getFirst().params().get("typology_id")).contains("stadium"));
        assertTrue(out.components().stream().noneMatch(c ->
                c.componentType() != null && c.componentType().toUpperCase().startsWith("ROOF")
                        && "mansard".equals(String.valueOf(c.params().get("roof_type")))));
    }

    @Test
    void baroqueFlatRoofAskDoesNotInventMansardOrDormers() {
        Component mansion = new Component(
                "STRUCTURE", "main",
                new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 18),
                List.of("typology:stadium_bowl"),
                Map.of("component_id", "mansion", "typology_id", "stadium_bowl"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Modern_Stadium_Elliptical")
                .components(List.of(mansion))
                .planMeta(Map.of("request_intent_text", "建造一座巴洛克风格的大宅，但屋顶使用平屋顶，不要老虎窗"))
                .build();

        LlmPlan out = StadiumMisrouteRepair.apply(plan);
        assertEquals("MASS_MAIN", out.components().getFirst().componentType());
        assertTrue(out.components().stream().noneMatch(c ->
                c.componentType() != null && c.componentType().toUpperCase().startsWith("ROOF")));
        assertNull(out.components().getFirst().params().get("roof_specialty"));
    }

    @Test
    void soleRealStadiumBesideCleanResidentialIsPreserved() {
        Component mansion = new Component(
                "MASS_MAIN", "main",
                new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 18),
                List.of("baroque"),
                Map.of("component_id", "main_house", "floor_count", 2));
        Component arena = new Component(
                "STRUCTURE", "arena",
                new Vec3i(60, 0, 0),
                new Dimensions(40, 60, 20),
                List.of("typology:stadium_bowl"),
                Map.of(
                        "component_id", "east_stadium",
                        "typology_id", "stadium_bowl",
                        "bowl_steepness", 0.6));
        Map<String, Object> hints = new HashMap<>();
        hints.put("field_to_bowl", 0.45);
        hints.put("bowl_depth_to_width", 0.3);
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("baroque")
                .components(List.of(mansion, arena))
                .proportionHints(hints)
                .planMeta(Map.of("request_intent_text", "建造一座巴洛克大宅，并在东侧建一座体育场"))
                .build();

        LlmPlan out = StadiumMisrouteRepair.apply(plan);
        assertEquals("MASS_MAIN", out.components().stream()
                .filter(c -> "main_house".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().componentType());
        Component east = out.components().stream()
                .filter(c -> "east_stadium".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("STRUCTURE", east.componentType());
        assertEquals("stadium_bowl", east.params().get("typology_id"));
        assertEquals(0.45, ((Number) out.proportionHints().get("field_to_bowl")).doubleValue(), 1e-6);
        assertEquals(0.3, ((Number) out.proportionHints().get("bowl_depth_to_width")).doubleValue(), 1e-6);
    }

    @Test
    void misroutedResidentialStructureDemotesWhileModuleArenaPreserved() {
        Component misrouted = new Component(
                "STRUCTURE", "main",
                new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 18),
                List.of("typology:stadium_bowl"),
                Map.of("component_id", "main", "typology_id", "stadium_bowl"));
        Component arena = new Component(
                "MODULE", "arena",
                new Vec3i(60, 0, 0),
                new Dimensions(42, 64, 22),
                List.of("typology:stadium_bowl"),
                Map.of(
                        "component_id", "east_arena",
                        "module_id", "stadium_bowl",
                        "typology_id", "stadium_bowl"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Modern_Stadium_Elliptical")
                .components(List.of(misrouted, arena))
                .planMeta(Map.of("request_intent_text",
                        "主楼是巴洛克大宅，东侧另外建一座体育场"))
                .build();

        LlmPlan out = StadiumMisrouteRepair.apply(plan);
        assertEquals("MASS_MAIN", out.components().stream()
                .filter(c -> "main".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().componentType());
        Component east = out.components().stream()
                .filter(c -> "east_arena".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("MODULE", east.componentType());
        assertEquals("stadium_bowl", east.params().get("typology_id"));
    }
}
