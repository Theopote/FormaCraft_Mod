package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class CircConflictDiagTest {
    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void dumpEvening5Bridge() throws Exception {
        var plan = LlmPlanParser.parse(Files.readString(Path.of(
                "python_backend/tests/fixtures/complex_build_retest/oct17_evening_5.normalized.json")));
        AssemblyCompileDiagnostics.clear();
        var patches = ComponentPlanCompiler.compile(plan);
        var gap = AssemblyCompileDiagnostics.get();
        System.out.println("patches=" + patches.size()
                + " gap=" + (gap == null ? "PASS" : gap.code() + " | " + gap.message()));
    }

    @Test
    void dumpOct16() throws Exception {
        var plan = LlmPlanParser.parse(Files.readString(Path.of(
                "python_backend/tests/fixtures/complex_build_retest/oct16_3.normalized.json")));
        plan = PlanIntentFidelityRepair.apply(
                StructureAccessoryRemapRepair.apply(
                        MassFootprintSeparationRepair.apply(plan)));
        // full preprocess subset: just intent after a clean parse via compile path pieces
        plan = LlmPlanParser.parse(Files.readString(Path.of(
                "python_backend/tests/fixtures/complex_build_retest/oct16_3.normalized.json")));
        AssemblyCompileDiagnostics.clear();
        var patches = ComponentPlanCompiler.compile(plan);
        var gap = AssemblyCompileDiagnostics.get();
        System.out.println("oct16 patches=" + patches.size()
                + " gap=" + (gap == null ? "PASS" : gap.code() + " | " + gap.message()));
        int[] fp = PlanIntentFidelityRepair.parseFootprint(PlanIntentFidelityRepair.intentCorpus(
                LlmPlanParser.parse(Files.readString(Path.of(
                        "python_backend/tests/fixtures/complex_build_retest/oct16_3.normalized.json")))));
        System.out.println("oct16 fp=" + fp[0] + "x" + fp[1]);
    }
}
