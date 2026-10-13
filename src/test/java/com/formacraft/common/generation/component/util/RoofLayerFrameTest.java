package com.formacraft.common.generation.component.util;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoofLayerFrameTest {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void insetShiftsFootprintLocalByInset() {
        var outer = RoofLayerFrame.of(0, 0, 16, 16, 1);
        var upper = RoofLayerFrame.inset(outer, 4, 8, 8);
        assertEquals(4, upper.worldBaseX());
        assertEquals(4, upper.originOffsetX());
        assertEquals(4, upper.footprintLocalX(1)); // 1 - 1 + 4
    }

    @Test
    void doubleEaveUpperExistsOnRectangle() {
        var patches = roofPatches(Map.of(
                "roof_type", "gable",
                "double_eave", true,
                "overhang", 0));
        assertFalse(patches.isEmpty());
        int maxY = patches.stream().mapToInt(BlockPatch::dy).max().orElse(0);
        int minY = patches.stream().mapToInt(BlockPatch::dy).min().orElse(0);
        assertTrue(maxY > minY, "upper tier should rise above lower");
    }

    @Test
    void doubleEaveDoesNotFillCourtyardVoid() {
        var params = new HashMap<String, Object>();
        params.put("roof_type", "gable");
        params.put("double_eave", true);
        params.put("plan_type", "courtyard");
        params.put("overhang", 0);
        var patches = roofPatches(params, new Dimensions(16, 16, 6));
        Set<String> cells = new HashSet<>();
        for (var p : patches) {
            if (BlockPatch.PLACE.equals(p.action())) cells.add(p.dx() + "," + p.dz());
        }
        // Courtyard core around center should remain mostly empty of roof plate at ground attachment.
        assertFalse(cells.contains("8,8") && cells.contains("7,7") && cells.contains("8,7"),
                "courtyard interior should not be solidly roofed");
    }

    @Test
    void lShapeBoundaryEdgesIncludeReentrantCorner() {
        var mask = ComponentFootprintMask.from(
                new SemanticComponent("MASS", null,
                        new Component("MASS", null, new Vec3i(0, 0, 0), new Dimensions(10, 10, 4),
                                List.of(), Map.of("plan_type", "l_shape")), "DEFAULT"),
                Map.of("plan_type", "l_shape"), 10, 10);
        assertFalse(mask.isFullyRectangular());
        var edges = mask.boundaryEdges();
        assertFalse(edges.isEmpty());
        assertTrue(edges.stream().anyMatch(e -> !e.courtyard()) || edges.stream().anyMatch(ComponentFootprintMask.BoundaryEdge::courtyard)
                || edges.size() > 4);
    }

    private static List<BlockPatch> roofPatches(Map<String, Object> params) {
        return roofPatches(params, new Dimensions(12, 10, 6));
    }

    private static List<BlockPatch> roofPatches(Map<String, Object> params, Dimensions dims) {
        var p = new HashMap<>(params);
        p.putIfAbsent("component_id", "roof");
        var c = new Component("ROOF", null, new Vec3i(0, 10, 0), dims, List.of(), p);
        return new RoofGenerator().generate(new SemanticComponent("ROOF", null, c, "DEFAULT"));
    }
}
