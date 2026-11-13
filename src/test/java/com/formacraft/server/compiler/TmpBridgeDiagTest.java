package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.BridgeWalkability;
import com.formacraft.common.generation.component.util.HostedPlateOccupancy;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
                "oct18_evening_5.normalized",
                "oct17_retest_3.normalized",
                "oct18_retry_5.normalized"
        }) {
            AssemblyCompileDiagnostics.clear();
            var plan = LlmPlanParser.parse(Files.readString(
                    Path.of("python_backend/tests/fixtures/complex_build_retest/" + n + ".json")));
            List<BlockPatch> patches = ComponentPlanCompiler.compile(plan);
            sb.append("PLAN ").append(n)
                    .append(" patches=").append(patches.size())
                    .append(" gap=").append(AssemblyCompileDiagnostics.get())
                    .append('\n');

            List<Component> comps = plan.components() == null ? List.of() : plan.components();
            List<Component> bridges = new ArrayList<>();
            List<Component> masses = new ArrayList<>();
            for (Component c : comps) {
                if (c == null || c.type() == null) continue;
                String t = c.type().toLowerCase(Locale.ROOT);
                if (t.contains("plate") || t.contains("deck") || t.contains("bridge")) {
                    if (HostedPlateOccupancy.isBridgePlate(c.params())) {
                        bridges.add(c);
                    }
                }
                if (t.contains("mass") || t.contains("box") || t.contains("cylinder") || t.contains("prism")) {
                    masses.add(c);
                }
            }
            sb.append("  bridges=").append(bridges.size()).append(" masses=").append(masses.size()).append('\n');
            var occ = BridgeWalkability.occupancyFromPatches(patches);
            for (Component plate : bridges) {
                String hostId = String.valueOf(plate.params() == null ? null : plate.params().get("host_id"));
                String targetId = HostedPlateOccupancy.targetHostId(plate.params()).orElse("?");
                Component host = findById(comps, hostId);
                Component target = findById(comps, targetId);
                var outcome = BridgeWalkability.evaluateBridgeDetailed(
                        occ, plate, BridgeWalkability.ZERO,
                        host, BridgeWalkability.ZERO,
                        target, BridgeWalkability.ZERO);
                sb.append("  bridge id=").append(idOf(plate))
                        .append(" host=").append(hostId)
                        .append(" target=").append(targetId)
                        .append(" y=").append(plate.relativePosition() == null ? "?" : plate.relativePosition().y())
                        .append(" reach=").append(outcome.reach())
                        .append(" precision=").append(outcome.precision())
                        .append('\n');
                if (host != null) {
                    int portals = HostedPlateOccupancy.bridgePortalRemovals(plate, host).size();
                    sb.append("    portals->host=").append(portals).append('\n');
                }
                if (target != null) {
                    int portals = HostedPlateOccupancy.bridgePortalRemovals(plate, target).size();
                    sb.append("    portals->target=").append(portals).append('\n');
                }
            }

            // Opening material samples: glass expectations vs actual
            int glassExpected = 0, glassMatched = 0, glassAir = 0, glassIron = 0, glassOther = 0;
            for (Component c : comps) {
                if (!OpeningMaterialAudit.isUserExplicitWindowMaterial(c)) continue;
                glassExpected++;
            }
            sb.append("  userExplicitWindowComponents=").append(glassExpected).append('\n');

            // Sample blocks of interest
            int air = 0, glass = 0, iron = 0;
            for (BlockPatch p : patches) {
                if (p == null || p.targetBlock() == null) continue;
                String b = p.targetBlock().toLowerCase(Locale.ROOT);
                if (b.contains("air") || "remove".equalsIgnoreCase(p.action())) air++;
                if (b.contains("glass")) glass++;
                if (b.contains("iron_block")) iron++;
            }
            sb.append("  patchCounts airish=").append(air)
                    .append(" glass=").append(glass)
                    .append(" iron_block=").append(iron)
                    .append('\n');
            sb.append('\n');
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println(sb);
    }

    private static String idOf(Component c) {
        if (c == null || c.params() == null) return "?";
        Object id = c.params().get("component_id");
        return id == null ? "?" : String.valueOf(id);
    }

    private static Component findById(List<Component> comps, String id) {
        if (id == null || id.isBlank() || "?".equals(id)) return null;
        for (Component c : comps) {
            if (id.equals(idOf(c))) return c;
        }
        return null;
    }
}
