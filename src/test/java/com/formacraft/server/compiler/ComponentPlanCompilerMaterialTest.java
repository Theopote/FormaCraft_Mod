package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentPlanCompilerMaterialTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    private LlmPlan plan(String wall) {
        var mass = new Component("MASS_MAIN", "s", new Vec3i(0, 0, 0), new Dimensions(12, 12, 8),
                List.of(), Map.of("anchor_mode", "min_corner", "plan_type", "rectangle", "wall_block", wall,
                "window_ratio", 0.0, "roof_type", "flat"));
        return LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("MODERN")
                .anchor(new Vec3i(0, 0, 0)).layout(new Layout(null, false, List.of())).components(List.of(mass)).build();
    }
    @Test void invalidAttributeProducesActionableGap() {
        var plan = LlmPlanTestFixtures.builder().styleAttributes(new StyleAttributes(null, "minecraft:not_a_block",
                null, null, null, null, null, null)).build();
        assertTrue(ComponentPlanCompiler.compile(plan).isEmpty());
        assertEquals("E_MATERIAL_INVALID", AssemblyCompileDiagnostics.get().code());
        assertTrue(AssemblyCompileDiagnostics.get().message().contains("wall_material"));
    }
    @Test void invalidComponentMaterialCannotReturnPartialSuccess() {
        assertTrue(ComponentPlanCompiler.compile(plan("minecraft:not_a_block")).isEmpty());
        assertNotNull(AssemblyCompileDiagnostics.get());
        assertEquals("E_MATERIAL_INVALID", AssemblyCompileDiagnostics.get().code());
        assertTrue(AssemblyCompileDiagnostics.get().message().contains("wall_block"));
    }
    @Test void explicitWallSurvivesWholePostProcessingPipeline() {
        var plan = plan("minecraft:stone_bricks");
        var before = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan));
        assertFalse(before.isEmpty());
        var after = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false));
        var explicit = before.entrySet().stream().filter(e -> "minecraft:stone_bricks".equals(e.getValue())).toList();
        assertFalse(explicit.isEmpty());
        for (var entry : explicit) assertEquals(entry.getValue(), after.get(entry.getKey()), "material at " + entry.getKey());
    }
}
