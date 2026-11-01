package com.formacraft.server.phase2;

import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 骨架：六类建筑样本走完 Compile → Preview digest → Apply，并做焦点启发式检查。
 * <p>
 * 不调用 LLM；种子 plan 仅保证管线可测。观感优化属于 Phase 3–4。
 */
class Phase2BuildingSampleRegressionTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void clearDiagnostics() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void catalogListsExactlySixCanonicalSamples() {
        List<String> ids = Phase2SampleCatalog.catalogIds();
        assertEquals(List.of(
                "small_house",
                "modern_villa",
                "medieval_tower",
                "east_asian_pavilion",
                "cathedral",
                "apartment"
        ), ids);
    }

    @Test
    void eachSampleDeclaresPromptFocusAndPlan() {
        for (Phase2SampleManifest m : Phase2SampleCatalog.loadAll()) {
            assertFalse(m.prompt().isBlank(), m.id() + " prompt");
            assertFalse(m.focus().isEmpty(), m.id() + " focus");
            assertNotNull(Phase2SampleCatalog.loadPlan(m), m.id() + " plan");
        }
    }

    @TestFactory
    Stream<DynamicTest> eachSamplePreviewMatchesApplyAndPassesFocusChecks() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Phase2SampleManifest manifest : Phase2SampleCatalog.loadAll()) {
            tests.add(DynamicTest.dynamicTest(manifest.id() + " — " + manifest.labelZh(), () -> {
                Phase2PipelineHarness.RunResult run = Phase2PipelineHarness.run(manifest);
                assertTrue(run.diagnostics().stream().noneMatch(d -> d.startsWith("compile_empty")),
                        () -> manifest.id() + " compile failed: " + run.diagnostics());
                assertFalse(run.patches().isEmpty(), manifest.id() + " empty patches");
                assertTrue(run.previewApplyAgree(),
                        () -> manifest.id() + " preview≠apply: " + run.diagnostics()
                                + " digest=" + run.previewDigest());

                List<String> violations = Phase2StructuralChecks.violations(manifest.checks(), run.metrics());
                assertTrue(violations.isEmpty(),
                        () -> manifest.id() + " focus violations=" + violations
                                + " metrics=" + run.metrics()
                                + " digest=" + run.previewDigest());

                if (manifest.goldenPreviewDigest() != null) {
                    assertEquals(manifest.goldenPreviewDigest(), run.previewDigest(),
                            manifest.id() + " golden digest drift");
                }
            }));
        }
        return tests.stream();
    }
}
