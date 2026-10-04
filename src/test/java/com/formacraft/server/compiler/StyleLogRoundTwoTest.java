package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.test.PatchTestSnapshot;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class StyleLogRoundTwoTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6})
    void loggedPlansRetainStoreysAndIndependentOpenings(int id) throws Exception {
        try (var in = getClass().getResourceAsStream("/style_log_cases_round2/case_" + id + (id == 1 ? "_normalized" : "") + ".json")) {
            var plan = LlmPlanParser.parseAndValidate(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            var blocks = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false));
            assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
            if (id == 2) {
                assertEquals("minecraft:smooth_stone", blocks.get(new Vec3i(-3,4,-2)), "second storey floor");
                for (int y : new int[]{2,5}) assertTrue(blocks.entrySet().stream().anyMatch(e -> e.getKey().y()==y
                        && e.getKey().z()==-6 && e.getKey().x() < -2 && e.getValue().contains("glass")), "window row " + y);
            }
            if (id == 3) assertFalse(blocks.entrySet().stream().anyMatch(e -> e.getKey().z()==6
                    && Math.abs(e.getKey().x())<=1 && e.getValue().contains("glass")), "door bay remains independent");
        }
    }
}
