package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class TrapezoidPlanCompileTest {
    @Test void canonicalPlanEmitsBothAuthoredEndWidths() throws Exception {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        try (var input = getClass().getResourceAsStream("/style_quality_20261115/trapezoid_volume.json")) {
            assertNotNull(input);
            var plan = LlmPlanParser.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            var result = ComponentPlanCompiler.compile(plan);
            assertFalse(result.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
            var placed = result.stream().filter(p -> BlockPatch.PLACE.equals(p.action())).toList();
            assertTrue(placed.stream().allMatch(p -> "minecraft:smooth_quartz".equals(p.targetBlock())));
            for (int y = 2; y < 6; y++) {
                int level = y;
                assertEquals(9L, placed.stream().filter(p -> p.dy() == level && p.dz() == 4).count());
                assertEquals(4L, placed.stream().filter(p -> p.dy() == level && p.dz() == 10).count());
            }
        }
    }
}
