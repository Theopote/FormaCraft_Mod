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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DenseEavesPagodaMisrouteRepairTest {

    @Test
    void stripsDenseEavesFromAsymmetricWastelandPavilion() {
        Component structure = new Component(
                "STRUCTURE", "tower",
                new Vec3i(0, 0, 0),
                new Dimensions(20, 20, 28),
                List.of("typology:dense_eaves_pagoda"),
                Map.of("component_id", "tower", "typology_id", "dense_eaves_pagoda", "footprint", "octagon"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(structure))
                .planMeta(Map.of(
                        "request_intent_text", "生成一座 20x20 格的非对称废土风格中式楼阁。"))
                .build();

        LlmPlan out = DenseEavesPagodaMisrouteRepair.apply(plan);
        Component c = out.components().get(0);
        assertEquals("MASS_MAIN", c.componentType());
        assertNotEquals("dense_eaves_pagoda", String.valueOf(c.params().get("typology_id")));
    }

    @Test
    void forcesSquareFootprintForFourCornerTower() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "tower_body");
        params.put("typology_id", "dense_eaves_pagoda");
        params.put("floor_count", 3);
        Component structure = new Component(
                "STRUCTURE", "tower",
                new Vec3i(0, 0, 0),
                new Dimensions(16, 16, 23),
                List.of("typology:dense_eaves_pagoda", "four_corner"),
                params);
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(structure))
                .planMeta(Map.of(
                        "request_intent_text", "生成一座 16x16 格的中式三层四角塔楼。"))
                .build();

        LlmPlan out = DenseEavesPagodaMisrouteRepair.apply(plan);
        Component c = out.components().get(0);
        assertEquals("square", c.params().get("footprint"));
        assertTrue(c.dimensions().height() >= 34, "height=" + c.dimensions().height());
        assertTrue(((Number) c.params().get("floor_height")).intValue() >= 6);
        // 四角塔 alone must not invent corner lanterns — only request/AI-planned lanterns.
        assertFalse(Boolean.TRUE.equals(c.params().get("corner_lanterns")));
        assertTrue(c.features() == null || c.features().stream()
                .noneMatch(f -> f != null && f.toLowerCase().contains("lantern")));
        assertEquals("dense_eaves_pagoda", c.params().get("typology_id"));
    }

    @Test
    void fourCornerWoodPostsDoNotForcePagodaStoreyHeight() {
        Component pavilion = new Component(
                "MASS_MAIN", "pavilion_1",
                new Vec3i(0, 0, 0),
                new Dimensions(5, 5, 3),
                List.of("column"),
                Map.of("component_id", "pav1_main", "open", true, "wall_type", "none"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(pavilion))
                .planMeta(Map.of(
                        "request_intent_text", "建三座开敞木亭，每座四角木柱支撑双坡屋顶。"))
                .playerFidelityNoticeZh("组合式生成「建三座…四角木柱…」")
                .build();

        LlmPlan out = DenseEavesPagodaMisrouteRepair.apply(plan);
        assertEquals(3, out.components().get(0).dimensions().height());
    }

    @Test
    void fourCornerTowerDoesNotRewriteAnnexMassDimensions() {
        Map<String, Object> towerParams = new HashMap<>();
        towerParams.put("component_id", "pagoda");
        towerParams.put("typology_id", "dense_eaves_pagoda");
        towerParams.put("floor_count", 3);
        Component tower = new Component(
                "STRUCTURE", "tower",
                new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 24),
                List.of("typology:dense_eaves_pagoda"),
                towerParams);
        Component annex = new Component(
                "MASS_SECONDARY", "annex",
                new Vec3i(20, 0, 0),
                new Dimensions(8, 10, 6),
                List.of("side_hall"),
                Map.of("component_id", "side_hall", "roof_type", "gable"));
        Component annexRoof = new Component(
                "ROOF", "annex",
                new Vec3i(20, 6, 0),
                new Dimensions(8, 10, 3),
                List.of(),
                Map.of("component_id", "side_roof", "host_id", "side_hall", "roof_type", "gable"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(tower, annex, annexRoof))
                .planMeta(Map.of("request_intent_text", "中式三层四角塔楼，旁附配殿"))
                .build();

        LlmPlan out = DenseEavesPagodaMisrouteRepair.apply(plan);
        assertFalse(out.hasCapabilityGap());
        Component outAnnex = out.components().stream()
                .filter(c -> "side_hall".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(8, outAnnex.dimensions().width());
        assertEquals(10, outAnnex.dimensions().depth());
        assertEquals(6, outAnnex.dimensions().height());
        assertEquals(3, out.components().size());
        assertEquals("gable", out.components().stream()
                .filter(c -> "side_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().params().get("roof_type"));
    }

    @Test
    void openTempleCompositionUnderPagodaCueEmitsGapWithoutWipingSatellites() {
        Component hall = new Component(
                "MASS_MAIN", "main",
                new Vec3i(0, 0, 0),
                new Dimensions(14, 10, 8),
                List.of(),
                Map.of("component_id", "main_hall", "roof_type", "gable"));
        Component wing = new Component(
                "MASS_SECONDARY", "wing",
                new Vec3i(16, 0, 0),
                new Dimensions(8, 8, 6),
                List.of(),
                Map.of("component_id", "wing", "roof_type", "gable"));
        Component roof = new Component(
                "ROOF", "main",
                new Vec3i(0, 8, 0),
                new Dimensions(14, 10, 4),
                List.of(),
                Map.of("component_id", "main_roof", "host_id", "main_hall", "roof_type", "xieshan"));
        Component windows = new Component(
                "FACADE_WINDOWS", "main",
                new Vec3i(0, 2, 0),
                new Dimensions(14, 1, 3),
                List.of(),
                Map.of("component_id", "main_win", "host_id", "main_hall"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(hall, wing, roof, windows))
                .planMeta(Map.of("request_intent_text", "寺观前院加一座宝塔，保留厢房与歇山屋顶"))
                .build();

        LlmPlan out = DenseEavesPagodaMisrouteRepair.apply(plan);
        assertTrue(out.hasCapabilityGap());
        assertEquals("E_DENSE_EAVES_PAGODA_ROUTE_UNSUPPORTED", out.capabilityGap().code());
        assertEquals(4, out.components().size());
        assertNotNull(out.components().stream()
                .filter(c -> "FACADE_WINDOWS".equals(c.componentType())).findFirst().orElse(null));
        assertEquals("xieshan", out.components().stream()
                .filter(c -> "main_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().params().get("roof_type"));
    }

    @Test
    void authoredPagodaBesideHallDoesNotEmitFalseGap() {
        Component hall = new Component(
                "MASS_MAIN", "main",
                new Vec3i(0, 0, 0),
                new Dimensions(14, 10, 8),
                List.of(),
                Map.of("component_id", "main_hall", "roof_type", "gable"));
        Component pagoda = new Component(
                "MASS_SECONDARY", "pagoda",
                new Vec3i(24, 0, 0),
                new Dimensions(8, 8, 22),
                List.of("pagoda", "tiered"),
                Map.of("component_id", "pagoda_tower", "roof_type", "none", "floor_count", 5));
        Component pagodaRoof = new Component(
                "ROOF", "pagoda",
                new Vec3i(24, 22, 0),
                new Dimensions(8, 8, 4),
                List.of("eaves"),
                Map.of("component_id", "pagoda_roof", "host_id", "pagoda_tower", "roof_type", "hip"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(hall, pagoda, pagodaRoof))
                .planMeta(Map.of("request_intent_text", "寺院大殿旁建一座宝塔"))
                .build();

        LlmPlan out = DenseEavesPagodaMisrouteRepair.apply(plan);
        assertFalse(out.hasCapabilityGap());
        assertEquals(3, out.components().size());
        assertEquals("gable", out.components().stream()
                .filter(c -> "main_hall".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().params().get("roof_type"));
    }

    @Test
    void lockedStoreyHeightIsNotOverwrittenViaFloorHeightAlias() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "tower_body");
        params.put("typology_id", "dense_eaves_pagoda");
        params.put("floor_count", 3);
        params.put("storey_height", 4);
        params.put("design_sources", Map.of("storey_height", "USER_EXPLICIT"));
        Component structure = new Component(
                "STRUCTURE", "tower",
                new Vec3i(0, 0, 0),
                new Dimensions(16, 16, 23),
                List.of("typology:dense_eaves_pagoda"),
                params);
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(structure))
                .planMeta(Map.of("request_intent_text", "中式三层四角塔楼，不要灯笼"))
                .build();

        LlmPlan out = DenseEavesPagodaMisrouteRepair.apply(plan);
        Component c = out.components().get(0);
        assertEquals(4, ((Number) c.params().get("storey_height")).intValue());
        assertFalse(Boolean.TRUE.equals(c.params().get("corner_lanterns")));
    }
}
