package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.PatchTestSnapshot;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class StyleLogRegressionTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    static com.formacraft.common.llm.dto.LlmPlan load(int id) throws Exception {
        try (var in = StyleLogRegressionTest.class.getResourceAsStream("/style_log_cases/case_" + id + ".json")) {
            return LlmPlanParser.parseAndValidate(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    @Test void modernBandUsesAuthoredMaterial() throws Exception {
        var plan = load(2);
        var detail = plan.components().stream().filter(c -> "DECOR_DETAIL".equals(c.componentType())).findFirst().orElseThrow();
        var patches = new com.formacraft.common.generation.component.impl.DecorDetailGenerator().generate(
                new com.formacraft.common.compiler.semantic.SemanticComponent("DECOR_DETAIL", null, detail, plan.styleProfile()));
        assertFalse(patches.isEmpty());
        assertTrue(patches.stream().allMatch(p -> "minecraft:light_gray_concrete".equals(p.targetBlock())));
    }
    @ParameterizedTest @ValueSource(ints={1,2,3,4,6})
    void actualLoggedPlansCompile(int id) throws Exception {
        var blocks = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(load(id), net.minecraft.util.math.BlockPos.ORIGIN, null, null, false));
        assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        if (id == 6) {
            for (int y=1;y<10;y++) for (int x=-7;x<=7;x++) {
                assertEquals("minecraft:stone_bricks", blocks.get(new Vec3i(x,y,-6)), "north wall " + x + "," + y);
                assertEquals("minecraft:stone_bricks", blocks.get(new Vec3i(x,y,6)), "south wall " + x + "," + y);
            }
            for (int y=1;y<10;y++) for (int z=-6;z<=6;z++) {
                assertEquals("minecraft:stone_bricks", blocks.get(new Vec3i(-7,y,z)), "west wall");
                assertEquals("minecraft:stone_bricks", blocks.get(new Vec3i(7,y,z)), "east wall");
            }
            assertFalse(blocks.containsKey(new Vec3i(0,5,0)), "hollow interior");
        }
        if (id == 2) assertTrue(blocks.values().stream().filter(b -> b.contains("glass")).count() >= 30, "modern glazing");
    }
}
