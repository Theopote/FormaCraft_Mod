package com.formacraft.common.generation.component.impl;

import com.formacraft.common.generation.component.util.ComponentFacadeRhythmPlanner;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FacadeWindowsRhythmTest {

    @Test
    void classicalRhythmPreset_avoidsModuloGridOnWidth13() {
        List<BlockPatch> rhythm = generate(13, 10, Map.of(
                "window_aspect", "vertical_bay",
                "rhythm_preset", "CLASSICAL_PILASTER_BAY"
        ));
        List<BlockPatch> legacy = generate(13, 10, Map.of(
                "window_aspect", "square",
                "rhythm", "regular"
        ));

        Set<Integer> rhythmAxes = rhythm.stream().map(BlockPatch::dx).collect(Collectors.toSet());
        assertTrue(rhythmAxes.contains(5));
        assertTrue(rhythmAxes.contains(6));
        assertTrue(rhythmAxes.contains(7));
        assertFalse(rhythmAxes.contains(0));
        assertFalse(rhythmAxes.contains(12));
    }

    @Test
    void classicalRhythmPreset_isBilateral() {
        List<BlockPatch> patches = generate(17, 8, Map.of(
                "window_aspect", "vertical_bay",
                "rhythm_preset", "CLASSICAL_PILASTER_BAY"
        ));
        Set<Integer> axes = patches.stream().map(BlockPatch::dx).collect(Collectors.toSet());
        for (int axis : axes) {
            assertTrue(axes.contains(16 - axis), "axis " + axis + " should have mirror partner");
        }
    }

    @Test
    void classicalRhythmPreset_skipsEntranceBayOnPrimaryFacade() {
        List<BlockPatch> patches = generate(13, 8, Map.of(
                "window_aspect", "vertical_bay",
                "rhythm_preset", "CLASSICAL_PILASTER_BAY",
                "reserve_entrance_bay", true
        ));
        Set<Integer> axes = patches.stream().map(BlockPatch::dx).collect(Collectors.toSet());
        assertFalse(axes.contains(5));
        assertFalse(axes.contains(6));
        assertFalse(axes.contains(7));
        assertTrue(axes.contains(1));
        assertTrue(axes.contains(10));
    }

    @Test
    void classicalRhythmPreset_narrowWestFacadeStillPlacesWindows() {
        List<BlockPatch> patches = generateFacing(
                GlobalConstraints.Facing.WEST,
                1,
                10,
                18,
                Map.of(
                        "window_aspect", "vertical_strip",
                        "rhythm_preset", "CLASSICAL_PILASTER_BAY",
                        "reserve_entrance_bay", true
                )
        );
        assertFalse(patches.isEmpty(), "narrow E/W facade should not produce zero window patches");
        Set<Integer> axes = patches.stream().map(BlockPatch::dz).collect(Collectors.toSet());
        assertTrue(axes.contains(4) || axes.contains(5) || axes.contains(6));
    }

    @Test
    void rhythmPlanReportsNoSideBaysWhenAxisMaxIsTen() {
        var plan = ComponentFacadeRhythmPlanner.fromRepeatingPattern(
                com.formacraft.common.facade.rhythm.RepeatingPattern.fromPresetId(
                        ComponentFacadeRhythmPlanner.PRESET_CLASSICAL_PILASTER_BAY),
                10,
                ComponentFacadeRhythmPlanner.PRESET_CLASSICAL_PILASTER_BAY
        );
        assertTrue(plan.active());
        assertFalse(plan.hasNonEntranceWindowAxes());
    }

    private static List<BlockPatch> generate(int width, int height, Map<String, Object> params) {
        return generateFacing(GlobalConstraints.Facing.SOUTH, width, 1, height, params);
    }

    private static List<BlockPatch> generateFacing(
            GlobalConstraints.Facing facing,
            int width,
            int depth,
            int height,
            Map<String, Object> params
    ) {
        Map<String, Object> p = new HashMap<>(params);
        Component c = new Component(
                "FACADE_WINDOWS",
                "",
                new Vec3i(0, 0, 0),
                new Dimensions(width, depth, height),
                List.of("facade_rhythm"),
                p
        );
        Slot slot = new Slot("", new Vec3i(0, 0, 0), facing, "default", null, null);
        SemanticComponent semantic = new SemanticComponent("FACADE_WINDOWS", slot, c, null, null, null);
        return new FacadeWindowsGenerator().generate(semantic);
    }
}
