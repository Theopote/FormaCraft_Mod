package com.formacraft.server.compiler;

import com.formacraft.common.generation.routing.GenerationAuthority;
import com.formacraft.common.generation.routing.PlanMetaAccess;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.proportion.OpeningGrammarResolver;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Design fidelity suite: fixtures under {@code /plans/fidelity/} must survive parse → compile
 * without silent design mutation (satellites, roof pitch, provenance locks).
 */
class DesignFidelityTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void clearDiagnostics() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void massOnlyWithoutSatellitesFailsWhenCompilerInferDisabled() {
        Component mass = new Component(
                "MASS_MAIN",
                "body",
                new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8),
                List.of(),
                Map.of());
        Map<String, Object> planMeta = new HashMap<>();
        planMeta.put("allow_compiler_satellites", false);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(mass))
                .planMeta(planMeta)
                .build();

        var patches = ComponentPlanCompiler.compile(plan, null, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_DESIGN_INCOMPLETE", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void massOnlyFixtureReportsDesignIncomplete() throws Exception {
        LlmPlan plan = loadFidelityPlan("mass_only_no_infer.json");
        assertFalse(Boolean.TRUE.equals(
                plan.planMeta() == null ? null : plan.planMeta().get("allow_compiler_satellites")));

        var patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_DESIGN_INCOMPLETE", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void authorityContractComponentsFixturePreservesLockedRatio() throws Exception {
        LlmPlan plan = loadFidelityPlan("authority_contract_components.json");
        assertEquals(GenerationAuthority.COMPONENTS, PlanMetaAccess.planGenerationAuthority(plan));
        assertFalse(PlanMetaAccess.allowTypologyEmptyFallback(null, plan.planMeta()));
        assertFalse(PlanMetaAccess.allowStructureEmptyFallback(null, plan.planMeta()));
        assertEquals("AI_PLANNED", plan.designSources().get("window_ratio"));

        Component facade = plan.components().stream()
                .filter(c -> c != null && "FACADE_WINDOWS".equalsIgnoreCase(c.componentType()))
                .findFirst()
                .orElseThrow();
        Component afterOpening = OpeningGrammarResolver.apply(plan, facade);
        assertEquals(0.42, ((Number) afterOpening.params().get("window_ratio")).doubleValue(), 1e-9);

        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
    }

    @Test
    void lockedWindowRatioSurvivesOpeningGrammarAndCompile() throws Exception {
        LlmPlan plan = loadFidelityPlan("locked_window_ratio.json");
        Component facade = plan.components().stream()
                .filter(c -> c != null && "FACADE_WINDOWS".equalsIgnoreCase(c.componentType()))
                .findFirst()
                .orElseThrow();
        assertEquals(0.42, ((Number) facade.params().get("window_ratio")).doubleValue(), 1e-9);

        Component afterOpening = OpeningGrammarResolver.apply(plan, facade);
        assertEquals(0.42, ((Number) afterOpening.params().get("window_ratio")).doubleValue(), 1e-9,
                "component-level AI_PLANNED window_ratio must not be clamped");

        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
        assertTrue(patches.size() > 20,
                "0.42 window_ratio plan should produce substantial facade opening geometry");
    }

    @Test
    void unknownRoofTypeFailsCompileWithUnsupportedError() throws Exception {
        LlmPlan plan = loadFidelityPlan("unknown_roof_type.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_ROOF_TYPE_UNSUPPORTED", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void conflictingRoofHeightAndPitchFailsCompile() throws Exception {
        LlmPlan plan = loadFidelityPlan("conflicting_roof_height_pitch.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_ROOF_GEOMETRY_CONFLICT", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void gothicSteepRoofDocumentsPitchFidelityBaseline() throws Exception {
        LlmPlan plan = loadFidelityPlan("gothic_steep_roof.json");
        assertEquals("AI_PLANNED", plan.designSources().get("roof_pitch_degrees"),
                "baseline: AI_PLANNED pitch must survive to compile geometry");
    }

    @Test
    void crownRequiredWithExplicitCrownCompiles() throws Exception {
        LlmPlan plan = loadFidelityPlan("crown_required_present.json");
        assertEquals("REQUIRED",
                ((Map<?, ?>) plan.planMeta().get("design_requirements")).get("crown"));

        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty(), "REQUIRED crown plan with explicit CROWN must compile");
    }

    @Test
    void gothicSteepRoofFixtureCompilesWithPitchAboveTechnicalCap() throws Exception {
        LlmPlan plan = loadFidelityPlan("gothic_steep_roof.json");

        assertEquals("AI_PLANNED", plan.designSources().get("roof_pitch_degrees"));
        assertEquals("AI_PLANNED", plan.designSources().get("roof_type"));
        assertFalse(Boolean.TRUE.equals(plan.planMeta().get("allow_compiler_satellites")));

        Component roof = plan.components().stream()
                .filter(c -> c != null && "ROOF".equalsIgnoreCase(c.componentType()))
                .findFirst()
                .orElseThrow();
        assertEquals(58, ((Number) roof.params().get("roof_pitch_degrees")).intValue());

        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty(), "gothic steep roof plan must produce geometry");

        // alignRoofToMass places the roof at mass.height-1; pitch 58° on span 20 → rise ≈ 16 (> cap 8).
        Component mass = plan.components().stream()
                .filter(c -> c != null && "MASS_MAIN".equalsIgnoreCase(c.componentType()))
                .findFirst()
                .orElseThrow();
        int roofBaseY = mass.dimensions().height() - 1;
        int maxRoofY = patches.stream()
                .filter(p -> p.dy() >= roofBaseY)
                .mapToInt(BlockPatch::dy)
                .max()
                .orElse(roofBaseY);
        int rise = maxRoofY - roofBaseY + 1;
        assertTrue(rise > 8, "expected steep gable rise > 8 after full compile, got " + rise);

        var blocks = PatchTestSnapshot.blocks(patches);
        assertFalse(blocks.isEmpty());
        assertTrue(blocks.keySet().stream().anyMatch(p -> p.y() >= roofBaseY + 9),
                "compiled blocks must reach above technical roof cap of 8");
    }

    private static LlmPlan loadFidelityPlan(String fileName) throws Exception {
        String path = "/plans/fidelity/" + fileName;
        try (var in = DesignFidelityTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing fixture " + path);
            String json = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            return LlmPlanParser.parseAndValidate(json);
        }
    }
}
