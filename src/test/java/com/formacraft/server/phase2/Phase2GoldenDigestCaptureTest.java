package com.formacraft.server.phase2;

import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pin / refresh {@code goldenPreviewDigest} in sample.json.
 * <p>
 * Refresh: {@code $env:PHASE2_WRITE_GOLDEN='true'; ./gradlew test --tests Phase2GoldenDigestCaptureTest.writeGoldenDigestsIntoSampleJson}
 */
class Phase2GoldenDigestCaptureTest {

    static boolean writeGoldenEnabled() {
        return Boolean.getBoolean("phase2.writeGolden")
                || "true".equalsIgnoreCase(System.getenv("PHASE2_WRITE_GOLDEN"));
    }

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void clear() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void allSamplesHavePinnedGoldenDigests() {
        for (Phase2SampleManifest m : Phase2SampleCatalog.loadAll()) {
            Phase2PipelineHarness.RunResult run = Phase2PipelineHarness.run(m);
            assertFalse(run.patches().isEmpty(), m.id() + " empty: " + run.diagnostics());
            assertTrue(run.previewApplyAgree(), m.id() + " mismatch: " + run.diagnostics());
            assertNotNull(m.goldenPreviewDigest(),
                    m.id() + " missing goldenPreviewDigest — set PHASE2_WRITE_GOLDEN=true and rerun writeGoldenDigestsIntoSampleJson");
            assertEquals(m.goldenPreviewDigest(), run.previewDigest(),
                    m.id() + " golden digest drift");
        }
    }

    @Test
    void writeGoldenDigestsIntoSampleJson() throws Exception {
        Assumptions.assumeTrue(writeGoldenEnabled(), "set PHASE2_WRITE_GOLDEN=true to refresh goldens");
        Path root = Path.of("src/test/resources/phase2/samples");
        Map<String, String> digests = new LinkedHashMap<>();
        for (Phase2SampleManifest m : Phase2SampleCatalog.loadAll()) {
            Phase2PipelineHarness.RunResult run = Phase2PipelineHarness.run(m);
            assertFalse(run.patches().isEmpty(), m.id() + " " + run.diagnostics());
            digests.put(m.id(), run.previewDigest());
            Path sample = root.resolve(m.id()).resolve("sample.json");
            String json = Files.readString(sample, StandardCharsets.UTF_8);
            String updated;
            if (json.contains("\"goldenPreviewDigest\": null")) {
                updated = json.replace(
                        "\"goldenPreviewDigest\": null",
                        "\"goldenPreviewDigest\": \"" + run.previewDigest() + "\"");
            } else if (json.contains("\"goldenPreviewDigest\":")) {
                updated = json.replaceAll(
                        "\"goldenPreviewDigest\"\\s*:\\s*\"[^\"]*\"",
                        "\"goldenPreviewDigest\": \"" + run.previewDigest() + "\"");
            } else {
                throw new IllegalStateException("no goldenPreviewDigest field in " + sample);
            }
            Files.writeString(sample, updated, StandardCharsets.UTF_8);
        }
        System.out.println("Wrote Phase2 goldens: " + digests);
    }
}
