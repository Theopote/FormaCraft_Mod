package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.LlmPlanTestFixtures;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.PatchTestSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentPlanCompilerFoundationFacadeTest {

    @Test
    void expandsFoundationAndWrapsFacadeToMassFootprint() {
        Map<String, Object> massParams = Map.of(
                "anchor_mode", "min_corner",
                "window_ratio", 0.2,
                "facade_profile", "vertical_pilasters"
        );
        Component mass = new Component(
                "MASS_MAIN",
                "s0",
                new Vec3i(0, 0, 0),
                new Dimensions(16, 20, 9),
                List.of("pilasters"),
                massParams
        );
        Component foundation = new Component(
                "FOUNDATION",
                "s0",
                new Vec3i(-2, -1, -2),
                new Dimensions(20, 20, 1),
                List.of(),
                Map.of("anchor_mode", "min_corner")
        );
        Component facade = new Component(
                "FACADE_WINDOWS",
                "s0",
                new Vec3i(0, 3, 0),
                new Dimensions(16, 16, 5),
                List.of(),
                Map.of("window_ratio", 0.2, "window_aspect", "vertical_strip")
        );

        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("DEFAULT")
                .anchor(new Vec3i(0, 64, 0))
                .globalConstraints(new GlobalConstraints(GlobalConstraints.Facing.EAST, null, null))
                .layout(new Layout(null, false, List.of()))
                .components(List.of(foundation, mass, facade))
                .build();


        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, null, null, null, false);
        assertFalse(patches.isEmpty());

        boolean backWallWindow = patches.stream().anyMatch(p ->
                p.dz() == 19 && p.targetBlock() != null
                        && (p.targetBlock().contains("glass") || p.targetBlock().contains("bars")));
        boolean interiorSliceWindow = patches.stream().anyMatch(p ->
                p.dz() == 15 && p.dx() > 0 && p.dx() < 15
                        && p.targetBlock() != null
                        && (p.targetBlock().contains("glass") || p.targetBlock().contains("bars")));

        assertTrue(backWallWindow, "wrap facade should reach mass back wall z=19");
        assertFalse(interiorSliceWindow, "should not place windows on interior z=15 slice");
        var finalBlocks = PatchTestSnapshot.blocks(patches);
        assertEquals(20 * 24, finalBlocks.keySet().stream().filter(p -> p.y() == -1).count());
        for (int x = -2; x < 18; x++) {
            for (int z = -2; z < 22; z++) {
                assertNotNull(finalBlocks.get(new Vec3i(x, -1, z)), "foundation must cover every cell including margin");
            }
        }
    }

    @Test
    void oddCenteredMassFoundationUsesSlotOffsetOnceAndPreservesVerticalPosition() {
        Component mass = new Component("MASS_MAIN", "s", new Vec3i(-3, 2, 11),
                new Dimensions(9, 7, 6), List.of(), Map.of("anchor_mode", "center", "plan_type", "rectangle"));
        Component foundation = new Component("FOUNDATION", "s", new Vec3i(-3, 0, 11),
                new Dimensions(9, 7, 1), List.of(), Map.of("anchor_mode", "center"));
        var slot = new com.formacraft.common.llm.dto.Slot("s", new Vec3i(-20, 3, 50),
                GlobalConstraints.Facing.SOUTH, null, null, null);
        LlmPlan plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("DEFAULT")
                .anchor(new Vec3i(100, 64, -100)).layout(new Layout(null, false, List.of(slot)))
                .components(List.of(foundation, mass)).build();
        var blocks = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, null, null, null, false));
        var platform = blocks.keySet().stream().filter(p -> p.y() == 3).toList();
        assertEquals(13 * 11, platform.size());
        assertEquals(-29, platform.stream().mapToInt(Vec3i::x).min().orElseThrow());
        assertEquals(-17, platform.stream().mapToInt(Vec3i::x).max().orElseThrow());
        assertEquals(56, platform.stream().mapToInt(Vec3i::z).min().orElseThrow());
        assertEquals(66, platform.stream().mapToInt(Vec3i::z).max().orElseThrow());
    }

    @Test
    void explicitRoofHeightIsNotOverriddenByInferredHeightParameter() {
        Component mass = new Component("MASS_MAIN", null, new Vec3i(-4, 0, 8),
                new Dimensions(9, 7, 6), List.of(), Map.of("anchor_mode", "min_corner", "plan_type", "rectangle"));
        Component roof = new Component("ROOF", null, new Vec3i(0, 10, 0),
                new Dimensions(9, 7, 4), List.of(), Map.of("roof_type", "gable"));
        LlmPlan plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("DEFAULT")
                .anchor(new Vec3i(0, 64, 0)).components(List.of(mass, roof)).build();
        var blocks = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, null, null, null, false));
        assertEquals(8, blocks.keySet().stream().mapToInt(Vec3i::y).max().orElseThrow(),
                "mass top at 5 + four roof layers must end at 8");
        assertNotNull(blocks.get(new Vec3i(0, 8, 11)));
    }
}
