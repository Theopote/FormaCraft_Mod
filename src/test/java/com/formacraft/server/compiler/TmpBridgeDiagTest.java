package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Temporary: compile failing fixtures so ComponentEmitPass BRIDGE_DIAG appends detail. */
class TmpBridgeDiagTest {
    @BeforeAll
    static void boot() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void dump() throws Exception {
        Path out = Path.of("build/tmp-bridge-diag.txt");
        StringBuilder sb = new StringBuilder();
        for (String n : new String[]{
                "oct17_evening_5.normalized",
                "oct17_retest_5.normalized",
                "oct18_evening_5.normalized"
        }) {
            AssemblyCompileDiagnostics.clear();
            var plan = LlmPlanParser.parse(Files.readString(
                    Path.of("python_backend/tests/fixtures/complex_build_retest/" + n + ".json")));
            var patches = ComponentPlanCompiler.compile(plan);
            var gap = AssemblyCompileDiagnostics.get();
            sb.append("PLAN ").append(n)
                    .append(" patches=").append(patches == null ? -1 : patches.size())
                    .append(" gap=").append(gap == null ? "null" : gap.code())
                    .append('\n');
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }
}
