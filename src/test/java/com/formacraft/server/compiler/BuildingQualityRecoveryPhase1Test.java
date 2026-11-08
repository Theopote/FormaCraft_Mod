package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.server.network.IntentFidelityMetrics;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 quality recovery: empty/house plans for pagoda/gate/garden must not compile
 * into a default gable house success path.
 */
class BuildingQualityRecoveryPhase1Test {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @BeforeEach
    void reset() {
        AssemblyCompileDiagnostics.clear();
        IntentFidelityMetrics.resetForTests();
    }

    @AfterEach
    void clear() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void emptyPagodaPlanFailsSemanticGate() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("DEFAULT")
                .anchor(new Vec3i(0, 64, 0))
                .distinguishingFeatures(List.of("白色藏式佛塔", "pagoda"))
                .components(List.of())
                .layout(new com.formacraft.common.llm.dto.Layout(
                        null, false,
                        List.of(new Slot("main", new Vec3i(0, 0, 0), null, null, null, null))))
                .build();

        Optional<CapabilityGap> gap = PlanSemanticValidator.check(plan);
        assertTrue(gap.isPresent());
        assertEquals("E_EMPTY_COMPONENTS", gap.get().code());
        assertTrue(PlanSemanticValidator.refusesResidentialAutofill(plan));
    }

    @Test
    void houseKitForPagodaFailsSemanticFidelity() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(9, 7, 6), List.of(), Map.of());
        Component roof = new Component(
                "ROOF", "main", new Vec3i(0, 6, 0),
                new Dimensions(11, 9, 3), List.of(), Map.of("roof_type", "gable"));
        Component facade = new Component(
                "FACADE_WINDOWS", "main", new Vec3i(0, 1, 0),
                new Dimensions(9, 1, 3), List.of(), Map.of("window_ratio", 0.3));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .distinguishingFeatures(List.of("中式古塔", "重檐", "塔刹"))
                .components(List.of(mass, roof, facade))
                .build();

        Optional<CapabilityGap> gap = PlanSemanticValidator.check(plan);
        assertTrue(gap.isPresent());
        assertEquals("E_SEMANTIC_FIDELITY", gap.get().code());
    }

    @Test
    void gothicBellTowerMassStackPassesTowerSemanticGate() {
        Component shaft = new Component(
                "MASS_MAIN", "tower", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 23),
                List.of("typology:gothic_bell_tower", "mixed_masonry:deepslate_bricks+andesite"),
                Map.of("component_id", "bell_tower_shaft", "shape", "rectangle"));
        Component belfry = new Component(
                "MASS_SECONDARY", "tower", new Vec3i(-1, 23, -1),
                new Dimensions(10, 10, 5),
                List.of("corbel_overhang:1", "pointed_arch_openings"),
                Map.of("component_id", "belfry", "host_id", "bell_tower_shaft"));
        Component spire = new Component(
                "ROOF", "tower", new Vec3i(-2, 28, -2),
                new Dimensions(12, 12, 7),
                List.of("steep_pyramidal_spire", "finial:lightning_rod"),
                Map.of("component_id", "spire", "host_id", "bell_tower_shaft", "roof_type", "pyramid"));
        Component windows = new Component(
                "FACADE_WINDOWS", "tower", new Vec3i(0, 4, 0),
                new Dimensions(8, 8, 16), List.of("long_slit_windows"),
                Map.of("host_id", "bell_tower_shaft"));
        Component entrance = new Component(
                "ENTRANCE", "tower", new Vec3i(3, 0, 0),
                new Dimensions(2, 1, 4), List.of("pointed_arch_portal"),
                Map.of("host_id", "bell_tower_shaft"));
        Map<String, Object> meta = new HashMap<>();
        meta.put("building_class", "tower_like");
        meta.put("request_intent", Map.of(
                "building_class", "tower_like",
                "confidence", 0.9,
                "original_text", "生成一座 8x8 格、高 35 格的哥特式四角钟楼。"));
        meta.put("request_intent_text", "生成一座 8x8 格、高 35 格的哥特式四角钟楼。");
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("Gothic_Cathedral")
                .components(List.of(shaft, belfry, spire, windows, entrance))
                .planMeta(meta)
                .build();
        assertTrue(PlanSemanticValidator.check(plan).isEmpty(),
                () -> PlanSemanticValidator.check(plan).map(CapabilityGap::message).orElse(""));
    }

    @Test
    void wastelandPavilionTowerLikeMetaDoesNotTripPagodaGate() {
        Component mass = new Component(
                "MASS_MAIN", "main_site", new Vec3i(2, 4, 2),
                new Dimensions(8, 6, 8), List.of("asymmetric Chinese pavilion body"),
                Map.of("component_id", "wasteland_chinese_pavilion_body", "roof_type", "single_slope"));
        Component roof = new Component(
                "ROOF", "main_site", new Vec3i(1, 14, 2),
                new Dimensions(8, 6, 1), List.of(),
                Map.of("host_id", "wasteland_chinese_pavilion_body", "roof_type", "single_slope"));
        Map<String, Object> meta = new HashMap<>();
        meta.put("building_class", "tower_like");
        meta.put("request_intent", Map.of(
                "building_class", "tower_like",
                "confidence", 0.72,
                "original_text", "生成一座 20x20 格的非对称废土风格中式楼阁。"));
        meta.put("request_intent_text", "生成一座 20x20 格的非对称废土风格中式楼阁。");
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(mass, roof))
                .planMeta(meta)
                .build();
        assertTrue(PlanSemanticValidator.check(plan).isEmpty(),
                () -> PlanSemanticValidator.check(plan).map(CapabilityGap::message).orElse(""));
    }

    @Test
    void romanTholosTempleMetaDoesNotTripChineseTempleGate() {
        Component cella = new Component(
                "MASS_MAIN", "slot_tholos", new Vec3i(0, 3, 0),
                new Dimensions(14, 14, 10), List.of("cylindrical cella", "roof_type:dome"),
                Map.of("component_id", "tholos_cella", "shape", "circle", "roof_type", "dome"));
        Component roof = new Component(
                "ROOF", "slot_tholos", new Vec3i(-2, 8, -2),
                new Dimensions(15, 18, 3), List.of("hemispherical dome"),
                Map.of("host_id", "tholos_cella", "roof_type", "dome"));
        Map<String, Object> meta = new HashMap<>();
        meta.put("building_class", "temple");
        meta.put("request_intent", Map.of(
                "building_class", "temple",
                "confidence", 0.92,
                "original_text", "生成一座半径为 9 的圆形罗马神殿。外围由 12 根石英柱环绕"));
        meta.put("request_intent_text", "生成一座半径为 9 的圆形罗马神殿。外围由 12 根石英柱环绕");
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("Classical_GrecoRoman")
                .components(List.of(cella, roof))
                .planMeta(meta)
                .build();
        assertTrue(PlanSemanticValidator.check(plan).isEmpty(),
                () -> PlanSemanticValidator.check(plan).map(CapabilityGap::message).orElse(""));
    }

    @Test
    void denseEavesPagodaStructurePassesSemanticGate() {
        Component structure = new Component(
                "STRUCTURE",
                "tower",
                new Vec3i(0, 0, 0),
                new Dimensions(7, 7, 21),
                List.of("dense_eaves_pagoda", "finial_spire"),
                Map.of("typology", "dense_eaves_pagoda", "levels", 3));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .distinguishingFeatures(List.of("白色藏式佛塔"))
                .components(List.of(structure))
                .build();

        assertTrue(PlanSemanticValidator.check(plan).isEmpty());
        assertTrue(PlanSemanticValidator.refusesResidentialAutofill(plan));
    }

    @Test
    void emptyPagodaCompileDoesNotRecoverAsGableHouse() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("allow_compiler_satellites", true);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .distinguishingFeatures(List.of("佛塔", "藏式"))
                .playerFidelityNoticeZh("组合式生成「白色藏式佛塔」")
                .components(List.of())
                .planMeta(meta)
                .layout(new com.formacraft.common.llm.dto.Layout(
                        null, false,
                        List.of(new Slot("main", new Vec3i(0, 0, 0), null, null, null, null))))
                .build();

        var patches = ComponentPlanCompiler.compile(plan, null, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        String code = AssemblyCompileDiagnostics.get().code();
        assertTrue(
                "E_EMPTY_COMPONENTS".equals(code) || "E_SEMANTIC_FIDELITY".equals(code),
                () -> "unexpected gap: " + AssemblyCompileDiagnostics.get());
    }

    @Test
    void gateHouseKitFailsWhenNotGateMorphology() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 6), List.of(), Map.of());
        Component roof = new Component(
                "ROOF", "main", new Vec3i(0, 6, 0),
                new Dimensions(10, 10, 3), List.of(), Map.of("roof_type", "gable"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .distinguishingFeatures(List.of("寺庙山门"))
                .components(List.of(mass, roof))
                .build();
        Optional<CapabilityGap> gap = PlanSemanticValidator.check(plan);
        assertTrue(gap.isPresent());
        assertEquals("E_SEMANTIC_FIDELITY", gap.get().code());
    }

    @Test
    void gardenLoneHouseFailsSemanticGate() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(10, 8, 5), List.of(), Map.of());
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .distinguishingFeatures(List.of("江南园林"))
                .components(List.of(mass))
                .build();
        Optional<CapabilityGap> gap = PlanSemanticValidator.check(plan);
        assertTrue(gap.isPresent());
        assertEquals("E_SEMANTIC_FIDELITY", gap.get().code());
    }

    @Test
    void requestIntentSurvivesWhenLlmDropsPagodaMarkers() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(9, 7, 6), List.of(), Map.of());
        Component roof = new Component(
                "ROOF", "main", new Vec3i(0, 6, 0),
                new Dimensions(11, 9, 3), List.of(), Map.of("roof_type", "gable"));
        // No distinguishing_features — LLM forgot 佛塔 entirely.
        LlmPlan raw = LlmPlanTestFixtures.builder()
                .components(List.of(mass, roof))
                .build();
        LlmPlan plan = RequestIntentInjector.ensure(raw, "生成一座白色藏式佛塔，主体尺寸7×7");
        assertTrue(PlanSemanticValidator.refusesResidentialAutofill(plan));
        Optional<CapabilityGap> gap = PlanSemanticValidator.check(plan);
        assertTrue(gap.isPresent());
        assertEquals("E_SEMANTIC_FIDELITY", gap.get().code());
    }

    @Test
    void crownOnHouseIsNotTowerMorphology() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(9, 7, 6), List.of(), Map.of());
        Component crown = new Component(
                "CROWN", "main", new Vec3i(0, 6, 0),
                new Dimensions(3, 3, 2), List.of("finial"), Map.of());
        Component roof = new Component(
                "ROOF", "main", new Vec3i(0, 6, 0),
                new Dimensions(11, 9, 3), List.of(), Map.of("roof_type", "gable"));
        // Avoid 「重檐」 here so roof-intent conflict does not mask morphology failure.
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().components(List.of(mass, crown, roof)).build(),
                "生成一座三层中式古塔，顶部有塔刹");
        Optional<CapabilityGap> gap = PlanSemanticValidator.check(plan);
        assertTrue(gap.isPresent());
        assertEquals("E_SEMANTIC_FIDELITY", gap.get().code());
    }

    @Test
    void explicitRoofRejectedWhenGableSubstituted() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(11, 9, 7), List.of(), Map.of());
        Component roof = new Component(
                "ROOF", "main", new Vec3i(0, 7, 0),
                new Dimensions(13, 11, 3), List.of(), Map.of("roof_type", "gable"));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().components(List.of(mass, roof)).build(),
                "生成歇山顶徽派民居，白墙黑瓦");
        Optional<CapabilityGap> gap = PlanSemanticValidator.check(plan);
        assertTrue(gap.isPresent());
        assertEquals("E_ROOF_INTENT_CONFLICT", gap.get().code());
    }

    @Test
    void intentFidelityMetricsDoNotTreatBuildAloneAsSemanticSuccess() {
        IntentFidelityMetrics.recordParse(true, "req-1", "白色藏式佛塔", null);
        IntentFidelityMetrics.recordOutcome(
                true, true, false,
                "req-1", "白色藏式佛塔", "tower_like", "DEFAULT",
                "E_SEMANTIC_FIDELITY", "components=house", "");
        IntentFidelityMetrics.recordAutofill(
                "req-1", List.of("SKIPPED_DEFAULT_GABLE"), null, Map.of());
        var snap = IntentFidelityMetrics.snapshot();
        assertEquals(2, snap.parseSuccess());
        assertEquals(1, snap.buildSuccess());
        assertEquals(0, snap.intentFidelitySuccess());
        assertEquals(1, snap.intentFidelityFail());
        assertEquals(1, snap.autofillEvents());
    }

    @Test
    void wrongPagodaFloorsFailIntentFidelityDespiteGeometry() {
        IntentFidelityMetrics.resetForTests();
        IntentFidelityMetrics.recordLayeredOutcome(
                true, true, true, false,
                "req-m", "三层中式古塔重檐", "tower_like", "DEFAULT",
                "E_PAGODA_MORPHOLOGY", "floors=7 expected=3", "");
        var snap = IntentFidelityMetrics.snapshot();
        assertEquals(1, snap.planValidSuccess());
        assertEquals(1, snap.geometrySuccess());
        assertEquals(1, snap.worldApplySuccess());
        assertEquals(0, snap.intentFidelitySuccess());
        assertEquals(1, snap.intentFidelityFail());
    }

    @Test
    void compileAttemptDoesNotCountWorldApply() {
        IntentFidelityMetrics.resetForTests();
        IntentFidelityMetrics.recordCompileAttempt(
                null, null, null, true, true, null);
        var snap = IntentFidelityMetrics.snapshot();
        assertEquals(1, snap.planValidSuccess());
        assertEquals(1, snap.geometrySuccess());
        assertEquals(0, snap.worldApplySuccess());
        assertEquals(0, snap.worldApplyFail());
        IntentFidelityMetrics.recordWorldApply(true, "tx-1", "SUCCESS");
        snap = IntentFidelityMetrics.snapshot();
        assertEquals(1, snap.worldApplySuccess());
    }

    @Test
    void explicitXieshanRejectedWhenHipSubstituted() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(11, 9, 7), List.of(), Map.of());
        Component roof = new Component(
                "ROOF", "main", new Vec3i(0, 7, 0),
                new Dimensions(13, 11, 3), List.of(), Map.of("roof_type", "hip"));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().components(List.of(mass, roof)).build(),
                "生成歇山顶徽派民居，白墙黑瓦");
        Optional<CapabilityGap> gap = PlanSemanticValidator.check(plan);
        assertTrue(gap.isPresent());
        assertEquals("E_ROOF_INTENT_CONFLICT", gap.get().code());
    }

    @Test
    void doubleEaveRejectedWhenPlainGableWithoutDoubleSignal() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(11, 9, 7), List.of(), Map.of());
        Component roof = new Component(
                "ROOF", "main", new Vec3i(0, 7, 0),
                new Dimensions(13, 11, 3), List.of(), Map.of("roof_type", "gable"));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().components(List.of(mass, roof)).build(),
                "生成重檐歇山殿堂");
        Optional<CapabilityGap> gap = PlanSemanticValidator.check(plan);
        assertTrue(gap.isPresent());
        assertEquals("E_ROOF_INTENT_CONFLICT", gap.get().code());
    }

    @Test
    void doubleEaveRejectedWhenPlainHipWithoutDoubleSignal() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(11, 9, 7), List.of(),
                Map.of("component_id", "hall"));
        Component roof = new Component(
                "ROOF", "main", new Vec3i(0, 7, 0),
                new Dimensions(13, 11, 3), List.of(),
                Map.of("roof_type", "hip", "host_id", "hall"));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().components(List.of(mass, roof)).build(),
                "生成重檐庑殿顶大殿");
        Optional<CapabilityGap> gap = PlanSemanticValidator.check(plan);
        assertTrue(gap.isPresent());
        assertEquals("E_ROOF_INTENT_CONFLICT", gap.get().code());
    }

    @Test
    void multiBuildingPavilionRoofDoesNotConflictPrimaryXieshan() {
        Component hall = new Component(
                "MASS_MAIN", "hall", new Vec3i(0, 0, 0),
                new Dimensions(15, 11, 9), List.of(),
                Map.of("component_id", "hall"));
        Component hallRoof = new Component(
                "ROOF", "hall", new Vec3i(0, 9, 0),
                new Dimensions(17, 13, 4), List.of(),
                Map.of("roof_type", "xieshan", "host_id", "hall"));
        Component pavilion = new Component(
                "MASS_MAIN", "pavilion", new Vec3i(20, 0, 0),
                new Dimensions(7, 7, 6), List.of(),
                Map.of("component_id", "pavilion"));
        Component pavilionRoof = new Component(
                "ROOF", "pavilion", new Vec3i(20, 6, 0),
                new Dimensions(9, 9, 3), List.of(),
                Map.of("roof_type", "pyramid", "host_id", "pavilion"));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder()
                        .components(List.of(hall, hallRoof, pavilion, pavilionRoof))
                        .build(),
                "生成歇山顶主殿，旁边一座攒尖亭");
        Optional<CapabilityGap> gap = PlanSemanticValidator.check(plan);
        assertTrue(gap.isEmpty(), () -> gap.map(CapabilityGap::code).orElse("ok"));
    }
}
