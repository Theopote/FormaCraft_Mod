package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

class AdobeFenceDiagTest {
    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void dump() throws Exception {
        var plan = LlmPlanParser.parse(Files.readString(Path.of(
                "src/test/resources/regressions/ingame_20261027_0105/011629.json")));
        var prepared = ComponentPlanCompiler.prepareComponents(plan, ComponentPlanCompiler.indexSlots(plan), false);
        for (var c : prepared.components()) {
            if (c == null || c.params() == null) continue;
            String id = String.valueOf(c.params().get("component_id"));
            if (id.toLowerCase().contains("rail") || id.toLowerCase().contains("stair")
                    || "STAIR_SYSTEM".equalsIgnoreCase(c.componentType())) {
                System.out.println("prep " + c.componentType() + " id=" + id
                        + " pos=" + c.relativePosition() + " dims=" + c.dimensions()
                        + " railing=" + c.params().get("railing")
                        + " placement=" + c.params().get("placement")
                        + " from=" + c.params().get("from") + " to=" + c.params().get("to"));
            }
        }
        var patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        Map<BlockPos, String> cells = new HashMap<>();
        for (var p : patches) {
            if (p != null) cells.put(new BlockPos(p.dx(), p.dy(), p.dz()), p.targetBlock());
        }
        int fences = 0;
        for (var e : cells.entrySet()) {
            if (e.getValue() != null && e.getValue().contains("fence")) {
                fences++;
                System.out.println("fence " + e.getKey() + " " + e.getValue());
            }
        }
        System.out.println("fenceCount=" + fences + " gap="
                + com.formacraft.server.assembly.AssemblyCompileDiagnostics.get());
    }
}
