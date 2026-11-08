package com.formacraft.server.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;

class FortressPortalDiagTest {
    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void dumpPortalCells() throws Exception {
        var mapper = new ObjectMapper();
        var plan = mapper.readValue(
                Path.of("python_backend/eval/fixtures/plans/p2_dark_fortress_13.json").toFile(),
                LlmPlan.class);
        // Apply the same early repairs as ComponentPlanCompiler before prepare.
        plan = DuplicateComponentIdRepair.apply(plan);
        plan = MassFootprintSeparationRepair.apply(plan);
        plan = CastleTowerEnrichmentRepair.apply(plan);
        plan = HostIdRemapRepair.apply(plan);
        plan = FlatRoofAttachmentRepair.apply(plan);
        plan = DuplicateComponentIdRepair.apply(plan);

        var slotMap = new HashMap<String, Slot>();
        if (plan.layout() != null && plan.layout().slots() != null) {
            for (var s : plan.layout().slots()) {
                if (s != null && s.slotId() != null) slotMap.put(s.slotId(), s);
            }
        }
        System.out.println("plan entrances before prepare:");
        plan.components().stream()
                .filter(c -> c != null && "ENTRANCE".equalsIgnoreCase(c.componentType()))
                .forEach(c -> System.out.println("  pre id=" + c.params().get("component_id")
                        + " host=" + c.params().get("host_id")
                        + " slot=" + c.slotId()));
        // Probe normalize path for portals
        for (var c : plan.components()) {
            if (c == null || c.params() == null) continue;
            if (!String.valueOf(c.params().get("component_id")).contains("portal")) continue;
            var nativeC = NativeBuildingComponentPolicy.apply(c, plan);
            var norm = ComponentSatelliteInference.normalizeComponent(nativeC);
            System.out.println("  normalize portal " + c.params().get("component_id")
                    + " -> " + (norm == null ? "NULL" : norm.componentType()));
        }
        var conflict = ExplicitDesignConflictRepair.apply(plan.components());
        System.out.println("after ExplicitDesignConflictRepair entrances="
                + conflict.stream().filter(c -> "ENTRANCE".equalsIgnoreCase(c.componentType())).count());
        conflict.stream().filter(c -> "ENTRANCE".equalsIgnoreCase(c.componentType()))
                .forEach(c -> System.out.println("  conflict id=" + c.params().get("component_id")));
        var prepared = ComponentSatelliteInference.prepareComponents(plan, slotMap, false);
        System.out.println("prepared entrances (" + prepared.components().stream()
                .filter(c -> c != null && "ENTRANCE".equalsIgnoreCase(c.componentType())).count() + "):");
        prepared.components().stream()
                .filter(c -> c != null && "ENTRANCE".equalsIgnoreCase(c.componentType()))
                .forEach(c -> System.out.println("  id=" + c.params().get("component_id")
                        + " host=" + c.params().get("host_id")
                        + " pos=" + c.relativePosition()
                        + " dims=" + c.dimensions()
                        + " facing=" + c.params().get("facing")));

        var raw = ComponentPlanCompiler.compile(
                mapper.readValue(Path.of("python_backend/eval/fixtures/plans/p2_dark_fortress_13.json").toFile(),
                        LlmPlan.class),
                BlockPos.ORIGIN, null, null, false);
        var blocks = PatchTestSnapshot.blocks(raw);
        var towers = plan.components().stream().filter(c -> "TOWER".equals(c.componentType())).toList();
        for (int i = 0; i < towers.size(); i++) {
            var p = towers.get(i).relativePosition();
            int cx = p.x() + 4, cz = p.z() + 4;
            int inside = i == 0 ? cx + 4 : cx - 4;
            System.out.println("tower" + i + " insideX=" + inside + " y1="
                    + blocks.get(new Vec3i(inside, 1, cz)));
        }
    }
}
