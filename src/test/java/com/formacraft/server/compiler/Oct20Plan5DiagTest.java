package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.HostedPlateOccupancy;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCirculationConstraints;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class Oct20Plan5DiagTest {
    @BeforeAll
    static void setup() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void dumpPortalsAndConflictCell() throws Exception {
        var plan = LlmPlanParser.parse(Files.readString(Path.of(
                "python_backend/tests/fixtures/complex_build_retest/oct20_access_5.normalized.json")));
        Component bridge = null;
        Map<String, Component> byId = new HashMap<>();
        for (Component c : plan.components()) {
            if (c == null || c.params() == null || c.params().get("component_id") == null) continue;
            String id = String.valueOf(c.params().get("component_id"));
            byId.put(id, c);
            if ("bridge_deck".equals(id)) bridge = c;
        }
        System.out.println("bridge=" + bridge.relativePosition() + " " + bridge.dimensions()
                + " host=" + bridge.params().get("host_id")
                + " target=" + bridge.params().get("target_host_id"));
        for (String end : List.of("host_id", "target_host_id")) {
            Object raw = bridge.params().get(end);
            if (raw == null) continue;
            Component mass = byId.get(String.valueOf(raw));
            if (mass == null) {
                System.out.println(end + "=" + raw + " MISSING");
                continue;
            }
            var portals = HostedPlateOccupancy.bridgePortalRemovals(bridge, mass);
            System.out.println(end + "=" + raw + " portals=" + portals.size());
            for (var p : portals) {
                if (p.dx() == 6 && p.dz() == 1 || p.dy() == 5 && Math.abs(p.dx() - 6) <= 2 && Math.abs(p.dz() - 1) <= 2) {
                    System.out.println("  portal " + p);
                }
            }
            System.out.println("  all=" + portals);
        }

        // Soften circulation conflict: capture flights then validate manually after compile soft path.
        AssemblyCompileDiagnostics.clear();
        // Force typology soft path is unavailable; compile and accept empty, then synthesize
        // portal flights and check final writers by compiling without post-validate via
        // package-visible emit is hard. Instead patch-validate using a one-shot override:
        var flights = new ArrayList<AssemblyCirculationConstraints.Flight>();
        List<BlockPatch> patches;
        try (var capture = AssemblyCirculationConstraints.captureTo(flights::addAll)) {
            patches = ComponentPlanCompiler.compile(plan);
        }
        System.out.println("gap=" + AssemblyCompileDiagnostics.get()
                + " patches=" + patches.size() + " published=" + flights.size());

        // Re-compile with circulation soft-fail by temporarily wrapping validatePatches is not
        // exposed. Dump stair flights alone for (6,5,1).
        for (String id : List.of("stair_dispatch_interior", "stair_platform_exterior")) {
            Component stair = byId.get(id);
            var stairFlights = new ArrayList<AssemblyCirculationConstraints.Flight>();
            try (var cap = AssemblyCirculationConstraints.captureTo(stairFlights::addAll)) {
                var gen = com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.generate(
                        new com.formacraft.common.compiler.semantic.SemanticComponent(
                                stair.componentType(), null, stair));
                System.out.println(id + " gen=" + gen.size() + " flights=" + stairFlights.size());
                int i = 0;
                for (var f : stairFlights) {
                    boolean hit = f.clearance().contains(new BlockPos(6, 5, 1))
                            || f.occupied().contains(new BlockPos(6, 5, 1));
                    System.out.println("  f" + (i++) + " occ=" + f.occupied().size()
                            + " clear=" + f.clearance().size() + " hit651=" + hit);
                    if (hit) {
                        System.out.println("  clear=" + f.clearance());
                        System.out.println("  occ=" + f.occupied());
                    }
                }
                for (var p : gen) {
                    if (p != null && p.dx() == 6 && p.dy() == 5 && p.dz() == 1) {
                        System.out.println("  genHit " + p);
                    }
                }
            }
        }
    }
}
