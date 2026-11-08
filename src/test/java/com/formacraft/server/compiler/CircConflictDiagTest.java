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
        var raw = LlmPlanParser.parse(Files.readString(Path.of(
                "python_backend/tests/fixtures/complex_build_retest/oct17_evening_5.normalized.json")));
        var plan = PlanIntentFidelityRepair.apply(MassFootprintSeparationRepair.apply(
                StructureAccessoryRemapRepair.apply(raw)));
        // Run the same early repairs as compile (subset) then dump bridge geometry.
        plan = com.formacraft.common.llm.parser.LlmPlanAnchorNormalizer.normalize(raw);
        plan = SlotPrimaryAnchorRepair.apply(plan);
        plan = RequestIntentInjector.ensure(plan, (String) null);
        plan = MassFootprintSeparationRepair.apply(plan);
        plan = HostedPlateLevelRepair.apply(plan);
        plan = PlanIntentFidelityRepair.apply(plan);
        plan = HostedPlateLevelRepair.apply(plan);
        for (var c : plan.components()) {
            if (c == null || c.params() == null) continue;
            String id = String.valueOf(c.params().get("component_id"));
            if (id.contains("office") || id.contains("workshop") || id.contains("corridor")
                    || id.contains("bridge") || id.contains("landing")) {
                var b = com.formacraft.common.generation.component.util.ComponentFootprintUtil.bounds(c);
                System.out.println(id + " type=" + c.componentType() + " slot=" + c.slotId()
                        + " pos=" + c.relativePosition() + " dim=" + c.dimensions()
                        + " bounds=" + b
                        + " host=" + c.params().get("host_id")
                        + " target=" + c.params().get("target_host_id")
                        + " extrude=" + c.params().get("extrude_mode")
                        + " plate_role=" + c.params().get("plate_role"));
            }
        }
        AssemblyCompileDiagnostics.clear();
        var patches = ComponentPlanCompiler.compile(raw);
        var gap = AssemblyCompileDiagnostics.get();
        System.out.println("patches=" + patches.size()
                + " gap=" + (gap == null ? "PASS" : gap.code() + " | " + gap.message()));
    }
}
