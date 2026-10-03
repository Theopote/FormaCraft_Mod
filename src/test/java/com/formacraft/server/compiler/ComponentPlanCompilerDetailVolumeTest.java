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
        var mass = new Component("MASS_MAIN", "main", new Vec3i(3, 4, -2), new Dimensions(9, 7, 12), List.of(),
            Map.of("anchor_mode", "center", "plan_type", "rectangle", "floor_height", 4, "roof_type", "flat"));
        var foundation = new Component("FOUNDATION", "main", new Vec3i(3, -2, -2), new Dimensions(13, 11, 1),
            List.of(), Map.of("anchor_mode", "center"));
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).anchor(new Vec3i(100, 64, 200))
            .styleProfile("DEFAULT").proportionHints(Map.of("floor_cornice", true))
            .layout(new Layout(null, false, List.of(new Slot("main", new Vec3i(20, 6, 30), GlobalConstraints.Facing.SOUTH,
                "RESIDENTIAL", null, null))))
            .components(List.of(foundation, mass)).build();
        var result = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, new BlockPos(100, 64, 200), null, null, false));
        // min corner = (3-4,4,-2-3), plus slot (20,6,30); fourth layer is y=10+3.
        String boundary = result.get(new Vec3i(19, 13, 25));
        assertNotNull(boundary); assertTrue(boundary.contains("half=top"), boundary);
        String below = result.get(new Vec3i(19, 12, 25));
        assertNotNull(below); assertFalse(below.contains("half=top"), below);
        assertTrue(result.keySet().stream().anyMatch(pos -> pos.y() < 10));
    }
    @Test void compiledMassesKeepDifferentFloorHeightsAndSlotOrigins() {
        var first = new Component("MASS_MAIN", "a", new Vec3i(0, 2, 0), new Dimensions(5, 5, 9), List.of(),
            Map.of("anchor_mode", "min_corner", "plan_type", "rectangle", "floor_height", 3, "roof_type", "flat"));
        var second = new Component("MASS_MAIN", "b", new Vec3i(0, -2, 0), new Dimensions(5, 5, 8), List.of(),
            Map.of("anchor_mode", "min_corner", "plan_type", "rectangle", "floor_height", 4, "roof_type", "flat"));
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("DEFAULT")
            .proportionHints(Map.of("floor_cornice", true))
            .layout(new Layout(null, false, List.of(
                new Slot("a", new Vec3i(10, 3, 0), GlobalConstraints.Facing.SOUTH, "RESIDENTIAL", null, null),
                new Slot("b", new Vec3i(30, 10, 20), GlobalConstraints.Facing.SOUTH, "RESIDENTIAL", null, null))))
            .components(List.of(first, second)).build();
        var result = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false));
        assertTrue(result.get(new Vec3i(10, 7, 0)).contains("half=top"));
        assertTrue(result.get(new Vec3i(30, 11, 20)).contains("half=top"));
        assertFalse(result.get(new Vec3i(30, 10, 20)).contains("half=top"));
    }

}
