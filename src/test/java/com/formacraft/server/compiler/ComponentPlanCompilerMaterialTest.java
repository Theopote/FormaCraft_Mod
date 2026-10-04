package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentPlanCompilerMaterialTest {
    private Component body(String id, int x, String roof) {
        return new Component("MASS_MAIN", "s", new Vec3i(x, 0, 0), new Dimensions(12, 12, 8), List.of(),
                Map.of("component_id", id, "anchor_mode", "min_corner", "plan_type", "rectangle",
                        "roof_block", roof, "roof_type", "flat", "window_ratio", 0.0));
    }
    @Test void twoBuildingsInheritDistinctRoofMaterialsAndExplicitRoofWins() {
        var a = body("a", 0, "minecraft:deepslate_tiles");
        var b = body("b", 30, "minecraft:spruce_planks");
        var roof = new Component("ROOF", "s", new Vec3i(30, 7, 0), new Dimensions(12, 12, 3), List.of(),
                Map.of("component_id", "b_roof", "host_id", "b", "roof_type", "flat", "roof_block", "minecraft:bricks",
                        "anchor_mode", "min_corner"));
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("MODERN")
                .anchor(new Vec3i(0, 0, 0)).layout(new Layout(null, false, List.of())).components(List.of(b, roof, a)).build();
        var output = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false));
        assertFalse(output.isEmpty(), String.valueOf(AssemblyCompileDiagnostics.get()));
        assertEquals("minecraft:deepslate_tiles", output.get(new Vec3i(5, 7, 5)));
        assertEquals("minecraft:bricks", output.get(new Vec3i(35, 7, 5)));
    }
    @Test void wholePlanReplaysAfterOtherPlansUseSharedPalettes() {
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("MEDIEVAL")
                .anchor(new Vec3i(0, 0, 0)).layout(new Layout(null, false, List.of()))
                .proportionHints(Map.of("design_seed", 123L))
                .components(List.of(body("a", 0, "minecraft:deepslate_tiles"))).build();
        var before = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false));
        assertFalse(before.isEmpty());
        ComponentPlanCompiler.compile(plan("minecraft:stone_bricks"), BlockPos.ORIGIN, null, null, false);
        assertEquals(before, PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false)));
    }
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
