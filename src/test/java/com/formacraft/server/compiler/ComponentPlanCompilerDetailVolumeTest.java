package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentPlanCompilerDetailVolumeTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }
    @Test void centeredMassWithSlotAndFoundationDecoratesItsOwnFourthLayer() {
        // roof_type=none avoids E_FLAT_ROOF_HOST_COVERAGE after FootprintCenterRepair recenters.
        var mass = new Component("MASS_MAIN", "main", new Vec3i(0, 4, 0), new Dimensions(9, 7, 12), List.of(),
            Map.of("anchor_mode", "center", "plan_type", "rectangle", "floor_height", 4, "roof_type", "none"));
        var foundation = new Component("FOUNDATION", "main", new Vec3i(0, -2, 0), new Dimensions(13, 11, 1),
            List.of(), Map.of("anchor_mode", "center"));
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).anchor(new Vec3i(100, 64, 200))
            .styleProfile("DEFAULT").proportionHints(Map.of("floor_cornice", true))
            .layout(new Layout(null, false, List.of(new Slot("main", new Vec3i(0, 6, 0), GlobalConstraints.Facing.SOUTH,
                "RESIDENTIAL", null, null))))
            .components(List.of(foundation, mass)).build();
        var result = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, new BlockPos(100, 64, 200), null, null, false));
        assertFalse(result.isEmpty(), String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get()));
        // Centered 9-wide mass + slot Y(+6); fourth storey band cornice at y=13.
        assertTrue(result.entrySet().stream().anyMatch(e -> e.getKey().y() == 13
                && e.getValue() != null && e.getValue().contains("half=top")),
                "fourth layer cornice");
        assertTrue(result.entrySet().stream().anyMatch(e -> e.getKey().y() == 12
                && e.getValue() != null && !e.getValue().contains("half=top")),
                "storey below cornice is full block");
        assertTrue(result.keySet().stream().anyMatch(pos -> pos.y() < 10));
    }
    @Test void compiledMassesKeepDifferentFloorHeightsAndSlotOrigins() {
        var first = new Component("MASS_MAIN", "a", new Vec3i(0, 2, 0), new Dimensions(5, 5, 9), List.of(),
            Map.of("anchor_mode", "min_corner", "plan_type", "rectangle", "floor_height", 3, "roof_type", "flat"));
        var second = new Component("MASS_MAIN", "b", new Vec3i(0, -2, 0), new Dimensions(5, 5, 8), List.of(),
            Map.of("anchor_mode", "min_corner", "plan_type", "rectangle", "floor_height", 4, "roof_type", "flat"));
        // Primary already at XZ origin; secondary keeps relative spacing after SlotPrimary.
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("DEFAULT")
            .proportionHints(Map.of("floor_cornice", true))
            .layout(new Layout(null, false, List.of(
                new Slot("a", new Vec3i(0, 3, 0), GlobalConstraints.Facing.SOUTH, "RESIDENTIAL", null, null),
                new Slot("b", new Vec3i(20, 10, 20), GlobalConstraints.Facing.SOUTH, "RESIDENTIAL", null, null))))
            .components(List.of(first, second)).build();
        var result = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false));
        assertFalse(result.isEmpty(), String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get()));
        assertTrue(result.get(new Vec3i(0, 7, 0)).contains("half=top"));
        assertTrue(result.get(new Vec3i(20, 11, 20)).contains("half=top"));
        assertFalse(result.get(new Vec3i(20, 10, 20)).contains("half=top"));
    }

}
