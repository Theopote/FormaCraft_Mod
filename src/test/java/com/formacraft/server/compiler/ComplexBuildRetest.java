package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ComplexBuildRetest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    /** Stronger usability sample on the controlled fidelity fixture (not patch-count alone). */
    @Test void fidelityInteriorStairLandingWalkableSample() throws Exception {
        var json = Files.readString(Path.of("src/test/resources/plans/fidelity/stair_upper_landing_walkable.json"));
        var plan = LlmPlanParser.parse(json);
        var compilation = ComponentPlanCompiler.compileWithCirculation(plan, net.minecraft.util.math.BlockPos.ORIGIN, null, null, false);
        assertFalse(compilation.patches().isEmpty(), String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(AssemblyCompileDiagnostics.hasGap(), String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(compilation.circulation().isEmpty(), "interior stair must publish a flight");
        var occ = com.formacraft.common.generation.component.util.BridgeWalkability
                .occupancyFromPatches(compilation.patches());
        var landing = new com.formacraft.common.llm.dto.Vec3i(7, 4, 3);
        assertTrue(com.formacraft.common.generation.component.util.WalkabilityGraph.canStand(occ, landing),
                "upper landing must be standable");
        assertTrue(compilation.circulation().getFirst().clearance().stream().anyMatch(p -> p.getY() == 4),
                "flight must open the upper floor band");
    }

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
                "oct17_retest_5.normalized", "oct17_retest_6.normalized",
                "oct18_1.normalized", "oct18_2.normalized", "oct18_3.normalized", "oct18_4.normalized",
                "oct18_5.normalized", "oct18_6.normalized",
                "oct18_retry_1.normalized", "oct18_retry_2.normalized", "oct18_retry_3.normalized",
                "oct18_retry_4.normalized", "oct18_retry_5.normalized", "oct18_retry_6.normalized",
                "oct18_evening_1.normalized", "oct18_evening_2.normalized", "oct18_evening_3.normalized",
                "oct18_evening_4.normalized", "oct18_evening_5.normalized", "oct18_evening_6.normalized",
                "oct18_night_1.normalized", "oct18_night_2.normalized", "oct18_night_3.normalized",
                "oct18_night_4.normalized", "oct18_night_5.normalized", "oct18_night_6.normalized",
                "oct18_late_1.normalized", "oct18_late_2.normalized", "oct18_late_3.normalized",
                "oct18_late_4.normalized", "oct18_late_5.normalized", "oct18_late_6.normalized",
                "oct18_final_1.normalized", "oct18_final_2.normalized", "oct18_final_3.normalized",
                "oct18_final_4.normalized", "oct18_final_5.normalized", "oct18_final_6.normalized",
                "oct18_access_1.normalized", "oct18_access_2.normalized", "oct18_access_3.normalized",
                "oct18_landmark_1.normalized", "oct18_landmark_2.normalized", "oct18_landmark_3.normalized",
                "oct18_landmark_5.normalized", "oct18_landmark_6.normalized", "oct18_landmark_7.normalized",
                "oct19_crash_1.normalized", "oct19_crash_2.normalized", "oct19_crash_3.normalized", "oct19_crash_4.normalized", "oct19_crash_5.normalized").map(n -> DynamicTest.dynamicTest("logged plan " + n, () -> {
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
