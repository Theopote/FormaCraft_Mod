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

class StyleLogRoundThreeTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6})
    void loggedPlansHaveUnblockedOpeningsAndAttachedSupport(int id) throws Exception {
        try (var in = getClass().getResourceAsStream("/style_log_cases_round3/case_" + id + ".json")) {
            var plan = LlmPlanParser.parseAndValidate(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            var blocks = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false));
            assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
            if (id < 6) assertTrue(blocks.values().stream().filter(v -> v.contains("glass")).count() > 20, "complete facade window layout");
            if (id == 1) {
                assertEquals("minecraft:spruce_planks", blocks.get(new Vec3i(5,3,6)), "canonical storey floor");
                assertNull(blocks.get(new Vec3i(5,4,6)), "floor is only one block thick");
                for (int x : new int[]{13,14}) for (int y : new int[]{1,2})
                    assertNull(blocks.get(new Vec3i(x,y,6)), "door cuts both wall layers");
                for (var e : blocks.entrySet()) if (e.getValue().contains("glass") && e.getKey().x()==14)
                    assertNull(blocks.get(new Vec3i(13,e.getKey().y(),e.getKey().z())), "window has no inner wall blockage");
            }
            if (id == 4) for (int x : new int[]{-15,15})
                assertNotNull(blocks.get(new Vec3i(x,4,0)), "foundation touches floor at y=5");
        }
    }
}
