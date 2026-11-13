package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.server.command.StyleVisualCases;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

class TmpStyleDiagTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    @Test void round3() throws Exception {
        try (var in = getClass().getResourceAsStream("/style_log_cases_round3/case_1.json")) {
            var plan = LlmPlanParser.parseAndValidate(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            var blocks = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false));
            System.out.println("floor(5,3,6)=" + blocks.get(new Vec3i(5,3,6)));
            System.out.println("floor(5,4,6)=" + blocks.get(new Vec3i(5,4,6)));
            long glassAny = blocks.values().stream().filter(v -> v.contains("glass")).count();
            System.out.println("glassAny=" + glassAny);
            blocks.entrySet().stream()
                    .filter(e -> e.getValue().contains("glass") && e.getKey().x() == 14)
                    .sorted(java.util.Comparator.comparingInt((Map.Entry<Vec3i, String> e) -> e.getKey().y())
                            .thenComparingInt(e -> e.getKey().z()))
                    .limit(30)
                    .forEach(e -> System.out.println("g " + e.getKey() + " -> " + e.getValue()
                            + " inner=" + blocks.get(new Vec3i(13, e.getKey().y(), e.getKey().z()))));
            System.out.println("y10 materials: " + blocks.entrySet().stream()
                    .filter(e -> e.getKey().y() == 10)
                    .collect(Collectors.groupingBy(Map.Entry::getValue, Collectors.counting())));
        }
    }

    @Test void modernFlat() throws Exception {
        var plan = StyleVisualCases.load("modern_flat");
        var blocks = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false));
        var roof = plan.components().getFirst().params().get("roof_block").toString();
        var wall = plan.components().getFirst().params().get("wall_block").toString();
        long roofN = blocks.entrySet().stream().filter(e -> e.getValue().equals(roof)).count();
        long roofHi = blocks.entrySet().stream().filter(e -> e.getKey().y() >= 10 && e.getValue().equals(roof)).count();
        int maxY = blocks.keySet().stream().mapToInt(Vec3i::y).max().orElse(-1);
        System.out.println("wall=" + wall + " roof=" + roof + " roofCount=" + roofN + " roofY>=10=" + roofHi + " maxY=" + maxY);
        System.out.println("y hist: " + blocks.keySet().stream().collect(Collectors.groupingBy(Vec3i::y, Collectors.counting())));
        System.out.println("roof y hist: " + blocks.entrySet().stream().filter(e -> e.getValue().equals(roof))
                .collect(Collectors.groupingBy(e -> e.getKey().y(), Collectors.counting())));
        System.out.println("y10 materials: " + blocks.entrySet().stream().filter(e -> e.getKey().y() == 10)
                .collect(Collectors.groupingBy(Map.Entry::getValue, Collectors.counting())));
    }
}
