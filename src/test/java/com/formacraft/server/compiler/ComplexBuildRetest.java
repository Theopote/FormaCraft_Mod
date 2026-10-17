package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ComplexBuildRetest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @TestFactory Stream<DynamicTest> loggedJavaCompilerFailures() {
        return Stream.of("2","3.normalized","4.normalized","5.normalized",
                "latest_1.normalized", "latest_2.normalized", "latest_3.normalized",
                "latest_4.normalized", "latest_5.normalized", "latest_6.normalized",
                "oct16_1.normalized", "oct16_2.normalized", "oct16_3.normalized", "oct16_4.normalized", "oct16_5.normalized",
                "oct17_1", "oct17_2", "oct17_retry_1", "oct17_retry_2", "oct17_retry_3",
                "oct17_retry_4", "oct17_retry_5.normalized", "oct17_retry_6", "oct17_retry_7.normalized",
                "oct17_evening_1", "oct17_evening_2.normalized", "oct17_evening_3", "oct17_evening_4",
                "oct17_evening_5.normalized", "oct17_evening_6", "oct17_evening_7",
                "oct17_late_1.normalized", "oct17_late_2.normalized", "oct17_late_3", "oct17_late_4.normalized", "oct17_late_5",
                "oct17_new_1.normalized", "oct17_new_3.normalized", "oct17_new_4.normalized", "oct17_new_5.normalized", "oct17_new_6.normalized",
                "oct17_retest_1.normalized", "oct17_retest_2.normalized", "oct17_retest_3.normalized", "oct17_retest_4.normalized",
                "oct17_retest_5.normalized", "oct17_retest_6.normalized").map(n -> DynamicTest.dynamicTest("logged plan " + n, () -> {
            var json = Files.readString(Path.of("python_backend/tests/fixtures/complex_build_retest/" + n + ".json"));
            var plan = LlmPlanParser.parse(json);
            for (boolean postProcess : new boolean[]{false, true}) {
                var output = postProcess ? ComponentPlanCompiler.compile(plan, net.minecraft.util.math.BlockPos.ORIGIN, null, null)
                        : ComponentPlanCompiler.compile(plan);
                assertFalse(output.isEmpty(), String.valueOf(AssemblyCompileDiagnostics.get()));
                assertFalse(AssemblyCompileDiagnostics.hasGap(), String.valueOf(AssemblyCompileDiagnostics.get()));
                assertTrue(output.size() > 1000, "A whole building must not regress to a handful of detail blocks");
                System.out.println("Replay " + n + " postProcess=" + postProcess + ": " + output.size() + " patches");
            }
        }));
    }
}
