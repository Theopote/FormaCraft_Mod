package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.DecorDetailGenerator;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Live0109RegressionTest {
    @BeforeAll static void init() { MinecraftRegistryTestBootstrap.initialize(); }
    private LlmPlan load(String name) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (var in = getClass().getResourceAsStream("/regressions/ingame_20261029_0109/" + name + ".json")) {
            return mapper.readValue(in, LlmPlan.class);
        }
    }
    @Test void stupaDecorBandIsCenteredOnTheTower() throws Exception {
        var c = load("stupa").components().stream()
                .filter(x -> "stupa_lotus_throne".equals(x.params().get("component_id"))).findFirst().orElseThrow();
        var blocks = new DecorDetailGenerator().generate(new SemanticComponent("DECOR_DETAIL", null, c, null, null, null));
        assertFalse(blocks.isEmpty());
        assertEquals(-5, blocks.stream().mapToInt(b -> b.dx()).min().orElseThrow());
        assertEquals(5, blocks.stream().mapToInt(b -> b.dx()).max().orElseThrow());
        assertEquals(-5, blocks.stream().mapToInt(b -> b.dz()).min().orElseThrow());
        assertEquals(5, blocks.stream().mapToInt(b -> b.dz()).max().orElseThrow());
    }
    @Test void explicitCornerDecorKeepsItsOrigin() {
        var c = new Component("DECOR_DETAIL", null, new Vec3i(7, 3, -9), new Dimensions(4, 6, 1),
                List.of(), Map.of("anchor_mode", "min_corner", "block", "minecraft:quartz_block"));
        var blocks = new DecorDetailGenerator().generate(new SemanticComponent("DECOR_DETAIL", null, c, null, null, null));
        assertEquals(7, blocks.stream().mapToInt(b -> b.dx()).min().orElseThrow());
        assertEquals(-9, blocks.stream().mapToInt(b -> b.dz()).min().orElseThrow());
    }
    @Test void liveStupaCompiles() throws Exception {
        var blocks = ComponentPlanCompiler.compile(load("stupa"), BlockPos.ORIGIN, null, null, false);
        assertNull(AssemblyCompileDiagnostics.get());
        assertFalse(blocks.isEmpty());
    }
    @Test void legacyGateLoadsBayClassesAndReportsItsCorruptCrown() throws Exception {
        // The old backend already injected a cupola into the Chinese roof.
        // Preserve that conflict check while verifying the former class-loading crash is gone.
        var blocks = ComponentPlanCompiler.compile(load("shaolin_gate"), BlockPos.ORIGIN, null, null, false);
        assertTrue(blocks.isEmpty());
        assertNotNull(AssemblyCompileDiagnostics.get());
        assertEquals("E_PATCH_CONFLICT", AssemblyCompileDiagnostics.get().code());
    }
    @AfterEach void clear() { AssemblyCompileDiagnostics.clear(); }
}
