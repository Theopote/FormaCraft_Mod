package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class Oct20Plan5DiagTest {
    @BeforeAll
    static void setup() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void dumpFlights() throws Exception {
        var plan = LlmPlanParser.parse(Files.readString(Path.of(
                "python_backend/tests/fixtures/complex_build_retest/oct20_access_5.normalized.json")));
        // Soften circulation conflict so we can inspect the finished patch list.
        var field = Class.forName("com.formacraft.server.compiler.ComponentPlanCompiler")
                .getDeclaredMethods();
        AssemblyCompileDiagnostics.clear();
        var flights = new java.util.ArrayList<com.formacraft.server.assembly.AssemblyCirculationConstraints.Flight>();
        java.util.List<BlockPatch> patches;
        try (var capture = com.formacraft.server.assembly.AssemblyCirculationConstraints.captureTo(flights::addAll)) {
            // Compile still hard-fails after emit; capture per-component publishes nest under emit.
            patches = ComponentPlanCompiler.compile(plan);
        }
        System.out.println("gap=" + AssemblyCompileDiagnostics.get() + " patches=" + patches.size()
                + " publishedFlights=" + flights.size());
        // Rebuild flights by replaying stair generators + portal logic is heavy; instead inspect
        // last writers around the conflict cell from a second compile that skips validation via
        // temporarily treating the plan as typology-exclusive is not available. Dump stair alone.
        for (var c : plan.components()) {
            if (c == null || c.params() == null) continue;
            if (!Boolean.TRUE.equals(c.params().get("exterior"))
                    && !"stair_dispatch_interior".equals(String.valueOf(c.params().get("component_id")))
                    && !"stair_platform_exterior".equals(String.valueOf(c.params().get("component_id")))
                    && !"bridge_deck".equals(String.valueOf(c.params().get("component_id")))) continue;
            System.out.println("comp " + c.componentType() + " id=" + c.params().get("component_id")
                    + " pos=" + c.relativePosition() + " dims=" + c.dimensions()
                    + " host=" + c.params().get("host_id")
                    + " target=" + c.params().get("target_host_id")
                    + " from=" + c.params().get("from") + " to=" + c.params().get("to"));
        }
        var stair = plan.components().stream()
                .filter(c -> c != null && c.params() != null
                        && "stair_dispatch_interior".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        var stairFlights = new java.util.ArrayList<com.formacraft.server.assembly.AssemblyCirculationConstraints.Flight>();
        try (var cap = com.formacraft.server.assembly.AssemblyCirculationConstraints.captureTo(stairFlights::addAll)) {
            var gen = com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.generate(
                    new com.formacraft.common.compiler.semantic.SemanticComponent("STRUCTURE", null, stair));
            System.out.println("interior stair patches=" + gen.size() + " flights=" + stairFlights.size());
            int i = 0;
            for (var f : stairFlights) {
                System.out.println("  stairFlight " + (i++) + " occ=" + f.occupied().size()
                        + " clear=" + f.clearance().size());
                if (f.clearance().contains(new BlockPos(6, 5, 1)) || f.occupied().contains(new BlockPos(6, 5, 1))) {
                    System.out.println("  HIT 6,5,1");
                }
                System.out.println("  clearSample=" + f.clearance().stream().limit(20).toList());
                System.out.println("  occSample=" + f.occupied().stream().limit(20).toList());
            }
        }
        var exterior = plan.components().stream()
                .filter(c -> c != null && c.params() != null
                        && "stair_platform_exterior".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        var extFlights = new java.util.ArrayList<com.formacraft.server.assembly.AssemblyCirculationConstraints.Flight>();
        try (var cap = com.formacraft.server.assembly.AssemblyCirculationConstraints.captureTo(extFlights::addAll)) {
            var gen = com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.generate(
                    new com.formacraft.common.compiler.semantic.SemanticComponent("STRUCTURE", null, exterior));
            System.out.println("exterior stair patches=" + gen.size() + " flights=" + extFlights.size());
            int i = 0;
            for (var f : extFlights) {
                System.out.println("  extFlight " + (i++) + " occ=" + f.occupied().size()
                        + " clear=" + f.clearance().size());
                System.out.println("  clearSample=" + f.clearance().stream().limit(20).toList());
                System.out.println("  occSample=" + f.occupied().stream().limit(20).toList());
                for (var p : List.of(new BlockPos(6, 5, 1), new BlockPos(5, 5, 1), new BlockPos(6, 4, 1), new BlockPos(6, 5, 0))) {
                    if (f.clearance().contains(p) || f.occupied().contains(p)) {
                        System.out.println("  HIT " + p.toShortString());
                    }
                }
            }
        }
    }
}
