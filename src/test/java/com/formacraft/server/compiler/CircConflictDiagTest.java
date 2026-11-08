package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/** Temporary compile dump for remaining ComplexBuildRetest gaps. */
class CircConflictDiagTest {
    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void dumpRemainingGaps() throws Exception {
        for (String name : new String[]{
                "oct17_2", "oct17_late_1.normalized", "oct18_access_2.normalized",
                "oct18_landmark_1.normalized", "oct19_crash_1.normalized"
        }) {
            var plan = LlmPlanParser.parse(Files.readString(Path.of(
                    "python_backend/tests/fixtures/complex_build_retest/" + name + ".json")));
            AssemblyCompileDiagnostics.clear();
            var patches = ComponentPlanCompiler.compile(plan);
            var gap = AssemblyCompileDiagnostics.get();
            System.out.println(name + " => "
                    + (gap == null ? "PASS size=" + patches.size()
                    : gap.code() + " | " + gap.message()));
        }
    }
}
