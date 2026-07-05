package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 LLM 自带的 ROOF/FACADE/ENTRANCE 会被贴回 MASS 的 min_corner，而不是沿用中心锚点坐标。
 */
class ComponentPlanCompilerRealignTest {

    @Test
    void realignsRoofAndFacadeToMassMinCorner() {
        LlmPlan plan = new LlmPlan(
                LlmPlan.Mode.build,
                "Chinese_Traditional",
                new Vec3i(0, 64, 0),
                new GlobalConstraints(GlobalConstraints.Facing.EAST, null, null),
                new Layout(null, false, List.of(
                        new Slot("villa_1", new Vec3i(0, 0, 0), GlobalConstraints.Facing.EAST, "RESIDENTIAL", null, null)
                )),
                List.of(
                        massMain(),
                        misalignedRoof(),
                        misalignedFacade(),
                        misalignedEntrance()
                ),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, null, null, null, false);
        assertFalse(patches.isEmpty());

        // MASS center (0,0,0) 12x8x6 -> min_corner (-6,0,-4); roof top y = 5
        boolean hasWallLow = patches.stream().anyMatch(p ->
                p.dx() == -6 && p.dy() == 0 && p.dz() == -4 && p.targetBlock() != null && !p.targetBlock().contains("air"));
        boolean hasRoofAtTop = patches.stream().anyMatch(p ->
                p.dx() == -6 && p.dy() == 5 && p.dz() == -4 && p.targetBlock() != null && !p.targetBlock().contains("air"));
        boolean hasWindowOnWall = patches.stream().anyMatch(p ->
                p.dx() == -6 && p.dy() == 2 && p.dz() == -2
                        && p.targetBlock() != null
                        && (p.targetBlock().contains("glass") || p.targetBlock().contains("bars")));

        assertEquals(true, hasWallLow, "expected wall at mass min_corner");
        assertEquals(true, hasRoofAtTop, "expected roof anchored to mass min_corner top");
        assertEquals(true, hasWindowOnWall, "expected facade windows on mass exterior");
    }

    @Test
    void roofSkipsCutCornerFootprint() {
        Map<String, Object> massParams = new HashMap<>();
        massParams.put("anchor_mode", "center");
        massParams.put("plan_type", "cut_corners");
        massParams.put("corner_cut", 2);
        massParams.put("roof_type", "flat");

        LlmPlan plan = new LlmPlan(
                LlmPlan.Mode.build,
                "Chinese_Traditional",
                new Vec3i(0, 64, 0),
                new GlobalConstraints(GlobalConstraints.Facing.EAST, null, null),
                new Layout(null, false, List.of(
                        new Slot("villa_1", new Vec3i(0, 0, 0), GlobalConstraints.Facing.EAST, "RESIDENTIAL", null, null)
                )),
                List.of(
                        new Component(
                                "MASS_MAIN",
                                "villa_1",
                                new Vec3i(0, 0, 0),
                                new Dimensions(12, 8, 6),
                                List.of(),
                                massParams
                        ),
                        new Component(
                                "ROOF",
                                "villa_1",
                                new Vec3i(0, 6, 0),
                                new Dimensions(12, 3, 6),
                                List.of(),
                                Map.of("roof_type", "flat")
                        )
                ),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, null, null, null, false);
        assertFalse(patches.isEmpty());

        boolean cutCornerRoof = patches.stream().anyMatch(p ->
                p.dx() == -6 && p.dz() == -4 && p.targetBlock() != null && !p.targetBlock().contains("air"));
        boolean interiorRoof = patches.stream().anyMatch(p ->
                p.dx() == -1 && p.dz() == 1 && p.targetBlock() != null && !p.targetBlock().contains("air"));

        assertFalse(cutCornerRoof, "cut corner cell should not receive roof blocks");
        assertTrue(interiorRoof, "interior footprint should still receive roof blocks");
    }

    private static Component massMain() {
        Map<String, Object> params = new HashMap<>();
        params.put("anchor_mode", "center");
        params.put("window_ratio", 0.4);
        params.put("roof_type", "xuanshan");
        return new Component(
                "MASS_MAIN",
                "villa_1",
                new Vec3i(0, 0, 0),
                new Dimensions(12, 8, 6),
                List.of(),
                params
        );
    }

    private static Component misalignedRoof() {
        Map<String, Object> params = Map.of("roof_type", "xuanshan", "overhang", 1);
        return new Component(
                "ROOF",
                "villa_1",
                new Vec3i(0, 6, 0),
                new Dimensions(14, 10, 3),
                List.of(),
                params
        );
    }

    private static Component misalignedFacade() {
        Map<String, Object> params = Map.of(
                "window_ratio", 0.4,
                "window_aspect", "vertical_strip",
                "rhythm", "regular"
        );
        return new Component(
                "FACADE_WINDOWS",
                "villa_1",
                new Vec3i(-5, 1, 0),
                new Dimensions(10, 1, 4),
                List.of(),
                params
        );
    }

    private static Component misalignedEntrance() {
        return new Component(
                "ENTRANCE",
                "villa_1",
                new Vec3i(0, 0, -5),
                new Dimensions(2, 1, 3),
                List.of(),
                Map.of("door_width", 2, "door_height", 3)
        );
    }
}
