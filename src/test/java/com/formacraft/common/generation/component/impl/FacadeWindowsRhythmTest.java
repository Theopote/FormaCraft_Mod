package com.formacraft.common.generation.component.impl;

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

    private static List<BlockPatch> generate(int width, int height, Map<String, Object> params) {
        Map<String, Object> p = new HashMap<>(params);
        Component c = new Component(
                "FACADE_WINDOWS",
                "",
                new Vec3i(0, 0, 0),
                new Dimensions(width, 1, height),
                List.of("facade_rhythm"),
                p
        );
        Slot slot = new Slot("", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH);
        SemanticComponent semantic = new SemanticComponent("FACADE_WINDOWS", slot, c, null, null, null);
        return new FacadeWindowsGenerator().generate(semantic);
    }
}
