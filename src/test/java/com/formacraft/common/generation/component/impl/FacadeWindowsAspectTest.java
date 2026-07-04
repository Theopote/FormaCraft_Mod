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

import static org.junit.jupiter.api.Assertions.assertTrue;

class FacadeWindowsAspectTest {

    @Test
    void verticalStrip_producesNarrowAxisSpan() {
        List<BlockPatch> patches = generateWithAspect("vertical_strip", 20, 8, 1);
        assertTrue(patches.size() > 0);
        // vertical strips: limited z depth (facade depth=1), varied x
        long distinctX = patches.stream().map(BlockPatch::dx).distinct().count();
        long distinctZ = patches.stream().map(BlockPatch::dz).distinct().count();
        assertTrue(distinctX <= 8, "vertical strip should not span full facade width");
        assertTrue(distinctZ <= 1);
    }

    @Test
    void horizontalStrip_spansFacadeWidth() {
        List<BlockPatch> patches = generateWithAspect("horizontal_strip", 16, 6, 1);
        assertTrue(patches.size() > 0);
        long distinctX = patches.stream().map(BlockPatch::dx).distinct().count();
        assertTrue(distinctX >= 10, "horizontal strip should span most of facade width");
    }

    @Test
    void arrowSlit_sparseWindows() {
        List<BlockPatch> square = generateWithAspect("square", 20, 10, 1);
        List<BlockPatch> slit = generateWithAspect("arrow_slit", 20, 10, 1);
        assertTrue(slit.size() < square.size());
    }

    private static List<BlockPatch> generateWithAspect(String aspect, int width, int height, int depth) {
        Map<String, Object> params = new HashMap<>();
        params.put("window_aspect", aspect);
        params.put("window_ratio", 0.15);
        Component c = new Component(
                "FACADE_WINDOWS",
                "",
                new Vec3i(0, 0, 0),
                new Dimensions(width, depth, height),
                List.of(),
                params
        );
        Slot slot = new Slot("", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH);
        SemanticComponent semantic = new SemanticComponent("FACADE_WINDOWS", slot, c, null, null, null);
        return new FacadeWindowsGenerator().generate(semantic);
    }
}
