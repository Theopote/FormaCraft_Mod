package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

        LlmPlan plan = new LlmPlan(
                LlmPlan.Mode.build,
                "DEFAULT",
                new Vec3i(0, 64, 0),
                new GlobalConstraints(GlobalConstraints.Facing.EAST, null, null),
                new Layout(null, false, List.of()),
                List.of(foundation, mass, facade),
                null, null, null, null, null, null, null, null, null, null, null, null, null
        );

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
    }
}
