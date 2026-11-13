package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.BridgeWalkability;
import com.formacraft.common.generation.component.util.HostedPlateOccupancy;
import com.formacraft.common.generation.component.util.WalkabilityGraph;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

class TmpBridgeDiagTest {
    private static final Vec3i Z = new Vec3i(0, 0, 0);

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
            // Capture patches even when gap set: compile returns empty on gap, so re-run internal? 
            // ComponentPlanCompiler.compile clears result on gap. Use compileAllowingGap if exists.
            List<BlockPatch> patches = ComponentPlanCompiler.compile(plan);
            var gap = AssemblyCompileDiagnostics.get();
            sb.append("PLAN ").append(n)
                    .append(" patches=").append(patches == null ? -1 : patches.size())
                    .append(" gap=").append(gap == null ? "null" : gap.code() + " | " + gap.message())
                    .append('\n');

            List<Component> comps = plan.components() == null ? List.of() : plan.components();
            for (Component c : comps) {
                if (c == null || c.params() == null) continue;
                if (!HostedPlateOccupancy.isBridgePlate(c.params())) continue;
                sb.append("  bridge ").append(c.params().get("component_id"))
                        .append(" y=").append(c.relativePosition() == null ? null : c.relativePosition().y())
                        .append(" fh=").append(c.params().get("floor_height"))
                        .append(" dims=").append(c.dimensions())
                        .append(" host=").append(c.params().get("host_id"))
                        .append(" target=").append(c.params().get("target_host_id"))
                        .append('\n');
                Component host = find(comps, String.valueOf(c.params().get("host_id")));
                Component target = find(comps, String.valueOf(c.params().get("target_host_id")));
                if (host != null) {
                    sb.append("    portalsHost=").append(HostedPlateOccupancy.bridgePortalRemovals(c, host).size())
                            .append(" hostDims=").append(host.dimensions())
                            .append(" hostBounds=").append(com.formacraft.common.generation.component.util.ComponentFootprintUtil.bounds(host))
                            .append('\n');
                }
                if (target != null) {
                    sb.append("    portalsTarget=").append(HostedPlateOccupancy.bridgePortalRemovals(c, target).size())
                            .append(" targetDims=").append(target.dimensions())
                            .append(" targetBounds=").append(com.formacraft.common.generation.component.util.ComponentFootprintUtil.bounds(target))
                            .append('\n');
                }
                sb.append("    plateBounds=").append(com.formacraft.common.generation.component.util.ComponentFootprintUtil.bounds(c)).append('\n');
            }

            // Opening expectations for material plans
            for (Component c : comps) {
                if (c == null || c.params() == null) continue;
                String id = String.valueOf(c.params().get("component_id"));
                if (!id.contains("reception") && !id.contains("workshop") && !id.contains("facade")) continue;
                sb.append("  openCand ").append(id)
                        .append(" type=").append(c.componentType())
                        .append(" glass=").append(c.params().get("glass_block"))
                        .append(" winMat=").append(c.params().get("window_material"))
                        .append(" cs=").append(c.params().get("constraint_source"))
                        .append(" wEx=").append(c.params().get("window_explicit"))
                        .append(" gEx=").append(c.params().get("glass_explicit"))
                        .append(" mEx=").append(c.params().get("material_explicit"))
                        .append(" userOpen=").append(OpeningMaterialAudit.isUserExplicitOpening(c))
                        .append('\n');
            }
            sb.append('\n');
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    private static Component find(List<Component> comps, String id) {
        if (id == null || id.isBlank() || "null".equals(id)) return null;
        for (Component c : comps) {
            if (c != null && c.params() != null && id.equals(String.valueOf(c.params().get("component_id")))) {
                return c;
            }
        }
        return null;
    }
}
