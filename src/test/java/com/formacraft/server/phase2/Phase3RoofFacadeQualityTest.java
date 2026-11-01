package com.formacraft.server.phase2;

import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 起点：在 Phase 2 管线骨架上增加屋顶覆盖 / 立面开口节奏检查。
 */
class Phase3RoofFacadeQualityTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void clear() {
        AssemblyCompileDiagnostics.clear();
    }

    @TestFactory
    Stream<DynamicTest> roofAndFacadeFocusQuality() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Phase2SampleManifest manifest : Phase2SampleCatalog.loadAll()) {
            tests.add(DynamicTest.dynamicTest(manifest.id() + " roof/facade", () -> {
                Phase2PipelineHarness.RunResult run = Phase2PipelineHarness.run(manifest);
                assertFalse(run.patches().isEmpty(),
                        () -> manifest.id() + " compile empty: " + run.diagnostics());
                assertTrue(run.previewApplyAgree(),
                        () -> manifest.id() + " preview≠apply: " + run.diagnostics());

                Phase3RoofFacadeQuality.Report report = Phase3RoofFacadeQuality.analyze(
                        run.patches(),
                        new LinkedHashSet<>(manifest.focus()),
                        manifest.checks().requireRoofLike(),
                        manifest.checks().requireOpeningLike());

                // Always print metrics in assertion messages for golden tuning.
                assertTrue(report.issues().isEmpty(),
                        () -> manifest.id()
                                + " issues=" + report.issues()
                                + " roof=" + report.roof()
                                + " facade=" + report.facade()
                                + " digest=" + run.previewDigest());
            }));
        }
        return tests.stream();
    }
}
