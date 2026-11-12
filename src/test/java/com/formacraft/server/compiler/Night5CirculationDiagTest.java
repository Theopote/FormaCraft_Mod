package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Temporary probe for night_5 / final_5 exterior stair collisions. */
class Night5CirculationDiagTest {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void probeNight5ConflictCell() throws Exception {
        probe("oct18_night_5.normalized", new BlockPos(9, 0, -8));
    }

    @Test
    void probeFinal5ConflictCell() throws Exception {
        probe("oct18_final_5.normalized", new BlockPos(4, 6, -8));
    }

    private void probe(String name, BlockPos conflict) throws Exception {
        var json = Files.readString(Path.of("python_backend/tests/fixtures/complex_build_retest/" + name + ".json"));
        var plan = LlmPlanParser.parse(json);
        System.out.println("=== " + name + " conflict=" + conflict.toShortString() + " ===");
        for (var c : plan.components()) {
            if (c == null || c.params() == null) continue;
            String id = String.valueOf(c.params().get("component_id"));
            var b = ComponentFootprintUtil.bounds(c);
            if (b == null) continue;
            var slot = plan.layout() == null || plan.layout().slots() == null ? null
                    : plan.layout().slots().stream().filter(s -> s != null && s.slotId() != null
                    && s.slotId().equals(c.slotId())).findFirst().orElse(null);
            int ox = slot == null || slot.anchor() == null ? 0 : slot.anchor().x();
            int oy = slot == null || slot.anchor() == null ? 0 : slot.anchor().y();
            int oz = slot == null || slot.anchor() == null ? 0 : slot.anchor().z();
            boolean in = conflict.getX() >= b.minX() + ox && conflict.getX() < b.maxX() + ox
                    && conflict.getY() >= b.minY() + oy && conflict.getY() < b.maxY() + oy
                    && conflict.getZ() >= b.minZ() + oz && conflict.getZ() < b.maxZ() + oz;
            if (in || (c.params().get("exterior") != null) || "plate".equals(String.valueOf(c.params().get("extrude_mode")))
                    || "ENTRANCE".equals(c.componentType())) {
                System.out.println(c.componentType() + " id=" + id + " slot=" + c.slotId()
                        + " bounds=[" + (b.minX()+ox) + ".." + (b.maxX()+ox) + ","
                        + (b.minY()+oy) + ".." + (b.maxY()+oy) + ","
                        + (b.minZ()+oz) + ".." + (b.maxZ()+oz) + "] inConflict=" + in
                        + " exterior=" + c.params().get("exterior")
                        + " plate_role=" + c.params().get("plate_role")
                        + " host=" + c.params().get("host_id")
                        + " target=" + c.params().get("target_host_id")
                        + " stair_connection=" + c.params().get("stair_connection"));
            }
        }
        AssemblyCompileDiagnostics.clear();
        var patches = ComponentPlanCompiler.compile(plan);
        System.out.println("gap=" + AssemblyCompileDiagnostics.get());
        System.out.println("patches=" + patches.size());
        Map<String, Integer> writers = new HashMap<>();
        for (var p : patches) {
            if (p.dx() == conflict.getX() && p.dy() == conflict.getY() && p.dz() == conflict.getZ()) {
                writers.merge(p.action() + ":" + p.targetBlock(), 1, Integer::sum);
            }
        }
        System.out.println("shellAtConflict=" + writers);
    }
}
