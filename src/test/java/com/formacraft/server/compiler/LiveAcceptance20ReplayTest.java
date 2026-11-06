package com.formacraft.server.compiler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.init.TypologySystemInitializer;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Twenty live-acceptance prompts covering towers/gates/temples/gardens/houses.
 * Fixtures are no-LLM frozen plans; prompts must stay verbatim for in-game replay.
 */
class LiveAcceptance20ReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final List<Case> CASES = List.of(
            new Case("01_tibetan_stupa_9", "生成一座白色藏式佛塔，主体尺寸9×9", "tower_like"),
            new Case("02_pagoda_3_chongyan", "生成一座三层中式古塔，重檐，顶部有塔刹", "tower_like"),
            new Case("03_pagoda_5", "生成一座五层中式宝塔，顶部有塔刹", "tower_like"),
            new Case("04_temple_gate", "生成一座中式寺庙山门", "gate"),
            new Case("05_paifang", "生成一座中式牌坊", "gate"),
            new Case("06_daoguan_single", "生成一座传统道观", "temple"),
            new Case("07_temple_compound", "生成一座寺庙建筑群，两进院落与厢房", "temple"),
            new Case("08_jiangnan_garden", "生成一座江南园林", "garden"),
            new Case("09_japanese_garden", "生成一座日式庭院", "garden"),
            new Case("10_huizhou_house", "生成一栋徽派民居，白墙黑瓦", "generic"),
            new Case("11_xieshan_stone_shrine", "生成一座歇山顶道观，石砖墙", "temple"),
            new Case("12_wudian_hall", "生成一座庑殿顶道观大殿", "temple"),
            new Case("13_wood_house_no_windows", "生成一座11×9的两层木屋，不要窗", "generic"),
            new Case("14_arched_entrance_house", "生成一座带拱形大门的石砖民居，尺寸13×11", "generic"),
            new Case("15_siheyuan_courtyard", "生成一座四合院庭院", "garden"),
            new Case("16_pagoda_7_white", "生成一座7×7三层密檐古塔，白墙", "tower_like"),
            new Case("17_daoguan_xieshan", "生成一座道观主殿，歇山顶", "temple"),
            new Case("18_japanese_tea_garden", "生成一座日式茶室庭院", "garden"),
            new Case("19_modern_flat_house", "生成一座简约现代平顶住宅，尺寸12×10", "generic"),
            new Case("20_pavilion_garden", "生成一座带亭子的江南园林", "garden")
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
    Stream<DynamicTest> replayTwentyAcceptancePrompts() {
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

            assertTowerMorphology(c, plan);

            // Typology STRUCTURE needs a live world for the adaptor path; tower morphology
            // is covered by the native builder above (same as Batch-4 recovery replay).
            if (!"tower_like".equals(c.buildingClass())) {
                List<?> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
                assertFalse(AssemblyCompileDiagnostics.hasGap(),
                        () -> "compile gap " + c.fixtureId() + ": " + AssemblyCompileDiagnostics.get());
                assertFalse(patches.isEmpty(), "expected non-empty patches for " + c.fixtureId());
            }
        }));
    }

    private static void assertTowerMorphology(Case c, LlmPlan plan) {
        List<Component> comps = plan.components() == null ? List.of() : plan.components();
        if ("20_pavilion_garden".equals(c.fixtureId())) {
            AcceptanceReplayMorphology.assertPavilionGardenHosts(comps);
            return;
        }
        if (!"tower_like".equals(c.buildingClass())) {
            return;
        }
        Component structure = comps.stream()
                .filter(x -> "STRUCTURE".equals(x.componentType()))
                .findFirst().orElseThrow();
        String typology = String.valueOf(structure.params().get("typology_id"));
        if ("tibetan_stupa".equals(typology)) {
            AcceptanceReplayMorphology.assertTibetanStupa(structure, BlockPos.ORIGIN);
        } else if ("dense_eaves_pagoda".equals(typology)) {
            AcceptanceReplayMorphology.assertDenseEavesPagoda(structure, new BlockPos(0, 64, 0));
        } else {
            fail("unexpected tower typology: " + typology);
        }
    }

    private static LlmPlan load(String fixtureId) throws Exception {
        String path = "/regressions/live_acceptance_20/" + fixtureId + ".json";
        try (InputStream in = LiveAcceptance20ReplayTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing fixture " + path);
            return MAPPER.readValue(in, LlmPlan.class);
        }
    }
}
