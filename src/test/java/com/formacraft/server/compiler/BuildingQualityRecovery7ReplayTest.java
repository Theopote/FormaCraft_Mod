package com.formacraft.server.compiler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.generation.component.util.RoofTypeCatalog;
import com.formacraft.common.generation.component.util.RoofTypeKind;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.common.typology.TypologyParams;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.generation.typology.builder.DenseEavesPagodaBuilder;
import com.formacraft.server.generation.typology.builder.PagodaVerticalSemantics;
import com.formacraft.server.generation.typology.builder.TibetanStupaBuilder;
import com.formacraft.server.init.TypologySystemInitializer;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch 4 — freeze the seven live acceptance prompts as no-LLM plan replay.
 * Fixture prompts must stay verbatim; do not rewrite user text to greenwash.
 */
class BuildingQualityRecovery7ReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final List<Case> CASES = List.of(
            new Case("01_tibetan_stupa", "生成一座白色藏式佛塔，主体尺寸7×7", "tower_like"),
            new Case("02_dense_eaves_pagoda", "生成一座三层中式古塔，重檐，顶部有塔刹", "tower_like"),
            new Case("03_temple_daoguan", "生成一座传统道观", "temple"),
            new Case("04_temple_gate", "生成一座中式寺庙山门", "gate"),
            new Case("05_huizhou_house", "生成一栋徽派民居，白墙黑瓦", "generic"),
            new Case("06_jiangnan_garden", "生成一座江南园林", "garden"),
            new Case("07_japanese_garden", "生成一座日式庭院", "garden")
    );

    private record Case(String fixtureId, String acceptancePrompt, String buildingClass) {}

    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
        TypologySystemInitializer.initialize();
    }

    @BeforeEach
    void reset() {
        AssemblyCompileDiagnostics.clear();
    }

    @AfterEach
    void clear() {
        AssemblyCompileDiagnostics.clear();
    }

    @TestFactory
    Stream<DynamicTest> replaySevenAcceptancePrompts() {
        return CASES.stream().map(c -> DynamicTest.dynamicTest(c.fixtureId() + " :: " + c.acceptancePrompt(), () -> {
            LlmPlan raw = load(c.fixtureId());
            assertEquals(c.acceptancePrompt(), String.valueOf(raw.planMeta().get("acceptance_prompt")),
                    "fixture prompt must stay verbatim");

            LlmPlan plan = RequestIntentInjector.ensure(raw, c.acceptancePrompt());
            @SuppressWarnings("unchecked")
            Map<String, Object> intent = (Map<String, Object>) plan.planMeta().get("request_intent");
            assertNotNull(intent);
            assertEquals(c.acceptancePrompt(), intent.get("original_text"));
            assertEquals(c.buildingClass(), intent.get("building_class"));

            Optional<CapabilityGap> gap = PlanSemanticValidator.check(plan, c.acceptancePrompt(), null);
            assertTrue(gap.isEmpty(), () -> "semantic gap for " + c.fixtureId() + ": " + gap.orElse(null));

            assertCaseMorphology(c, plan);

            if (!"tower_like".equals(c.buildingClass())) {
                List<?> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
                assertFalse(AssemblyCompileDiagnostics.hasGap(),
                        () -> "compile gap " + c.fixtureId() + ": " + AssemblyCompileDiagnostics.get());
                assertFalse(patches.isEmpty(), "expected non-empty patches for " + c.fixtureId());
            }
        }));
    }

    private static void assertCaseMorphology(Case c, LlmPlan plan) {
        List<Component> comps = plan.components() == null ? List.of() : plan.components();
        switch (c.fixtureId()) {
            case "01_tibetan_stupa" -> {
                Component structure = comps.stream()
                        .filter(x -> "STRUCTURE".equals(x.componentType()))
                        .findFirst().orElseThrow();
                Map<String, Object> merged = TypologyParamResolver.merge(
                        TibetanStupaBuilder.TYPOLOGY_ID, structure);
                assertEquals("tibetan_stupa", merged.get("typology_id"));
                GeneratedStructure built = TibetanStupaBuilder.generate(
                        merged, BlockPos.ORIGIN, null, null);
                assertNotNull(built);
                assertFalse(built.getBlocks().isEmpty());
                assertTrue(built.getDescription().contains("tibetan_stupa"));
            }
            case "02_dense_eaves_pagoda" -> {
                Component structure = comps.stream()
                        .filter(x -> "STRUCTURE".equals(x.componentType()))
                        .findFirst().orElseThrow();
                Map<String, Object> merged = TypologyParamResolver.merge(
                        DenseEavesPagodaBuilder.TYPOLOGY_ID, structure);
                PagodaVerticalSemantics v = PagodaVerticalSemantics.resolve(
                        new TypologyParams(merged), 13, 7, 15);
                assertEquals(3, v.floorCount());
                assertEquals(2, v.eavesPerLevel());
                assertEquals(6, v.eaveTiers());
                GeneratedStructure built = DenseEavesPagodaBuilder.generate(
                        merged, BlockPos.ORIGIN, null, null);
                assertNotNull(built);
                assertFalse(built.getBlocks().isEmpty());
                assertTrue(built.getDescription().contains("floors=3"));
                assertTrue(built.getDescription().contains("eaves_per_level=2"));
            }
            case "04_temple_gate" -> {
                assertTrue(comps.stream().anyMatch(x -> "GATE".equals(x.componentType())));
                Component roof = comps.stream()
                        .filter(x -> "ROOF".equals(x.componentType()))
                        .findFirst().orElseThrow();
                assertEquals("gate_main", roof.params().get("host_id"));
            }
            case "05_huizhou_house" -> {
                Component roof = comps.stream()
                        .filter(x -> "ROOF".equals(x.componentType()))
                        .findFirst().orElseThrow();
                Object roofType = roof.params().get("roof_type");
                assertNotNull(roofType);
                assertNotEquals("gable", String.valueOf(roofType).toLowerCase());
            }
            case "07_japanese_garden" -> {
                boolean hasIrimoya = comps.stream().anyMatch(x -> {
                    Object rt = x.params() == null ? null : x.params().get("roof_type");
                    return rt != null && String.valueOf(rt).toLowerCase().contains("irimoya");
                });
                assertTrue(hasIrimoya, "japanese garden fixture must keep irimoya token");
                assertEquals(RoofTypeKind.XIESHAN, RoofTypeCatalog.parseExplicit("irimoya").orElseThrow());
                assertTrue(RoofTypeCatalog.isExecutableExplicit("irimoya"));
            }
            default -> {
                // temple / jiangnan: semantic gate + compile cover the contract
            }
        }
    }

    private static LlmPlan load(String fixtureId) throws Exception {
        String path = "/regressions/building_quality_recovery_7/" + fixtureId + ".json";
        try (InputStream in = BuildingQualityRecovery7ReplayTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing fixture " + path);
            return MAPPER.readValue(in, LlmPlan.class);
        }
    }
}
