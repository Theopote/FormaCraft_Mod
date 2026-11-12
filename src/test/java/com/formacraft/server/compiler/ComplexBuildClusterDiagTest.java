package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Temporary: cluster remaining ComplexBuildRetest capability gaps. */
class ComplexBuildClusterDiagTest {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void clusterRemaining() throws Exception {
        String[] names = {
                "oct18_final_5.normalized"
        };
        var byCode = new TreeMap<String, List<String>>();
        int pass = 0;
        for (String name : names) {
            var json = Files.readString(Path.of("python_backend/tests/fixtures/complex_build_retest/" + name + ".json"));
            var plan = LlmPlanParser.parse(json);
            AssemblyCompileDiagnostics.clear();
            var patches = ComponentPlanCompiler.compile(plan);
            var gap = AssemblyCompileDiagnostics.get();
            String code;
            String detail;
            if (!patches.isEmpty() && !AssemblyCompileDiagnostics.hasGap() && patches.size() > 1000) {
                code = "PASS";
                detail = "size=" + patches.size();
                pass++;
            } else if (gap != null) {
                code = gap.code() == null ? "NULL_CODE" : gap.code();
                detail = String.valueOf(gap.message());
                if (detail.length() > 120) detail = detail.substring(0, 120);
            } else if (patches.isEmpty()) {
                code = "EMPTY_NO_GAP";
                detail = "empty";
            } else {
                code = "SMALL_PATCHES";
                detail = "size=" + patches.size();
            }
            byCode.computeIfAbsent(code, k -> new ArrayList<>()).add(name + " | " + detail);
            System.out.println(name + " => " + code + " | " + detail);
        }
        System.out.println("=== SUMMARY pass=" + pass + "/" + names.length + " ===");
        byCode.forEach((code, rows) -> {
            System.out.println(rows.size() + " " + code);
            rows.forEach(r -> System.out.println("  " + r));
        });
    }
}
