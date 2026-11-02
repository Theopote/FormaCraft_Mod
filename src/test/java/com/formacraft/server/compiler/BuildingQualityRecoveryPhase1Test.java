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
}
