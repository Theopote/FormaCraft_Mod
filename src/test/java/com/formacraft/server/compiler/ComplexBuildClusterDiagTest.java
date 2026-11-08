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
                "4.normalized", "5.normalized", "latest_4.normalized", "latest_6.normalized",
                "oct16_3.normalized", "oct17_2", "oct17_retry_4", "oct17_retry_5.normalized",
                "oct17_retry_7.normalized", "oct17_evening_2.normalized", "oct17_evening_4",
                "oct17_evening_5.normalized", "oct17_evening_7", "oct17_late_1.normalized",
                "oct17_late_2.normalized", "oct17_late_3", "oct17_late_4.normalized",
                "oct17_new_1.normalized", "oct17_new_3.normalized", "oct17_new_5.normalized",
                "oct17_retest_3.normalized", "oct18_3.normalized", "oct18_retry_5.normalized",
                "oct18_evening_5.normalized", "oct18_night_2.normalized", "oct18_night_3.normalized",
                "oct18_night_5.normalized", "oct18_late_4.normalized", "oct18_late_5.normalized",
                "oct18_final_2.normalized", "oct18_final_3.normalized",
                "oct18_access_1.normalized", "oct18_access_2.normalized", "oct18_access_3.normalized",
                "oct18_landmark_1.normalized", "oct18_landmark_3.normalized", "oct18_landmark_6.normalized",
                "oct19_crash_1.normalized"
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
