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

import static org.junit.jupiter.api.Assertions.*;

class FacadeWindowsAspectTest {
    @Test
    void localWindowPatchKeepsItsOwnRectangleAcrossHostFloorBands() {
        var params = new HashMap<String,Object>();
        params.put("localized_window",true);
        params.put("localized_face","NORTH");
        params.put("window_width",5);
        params.put("window_height",4);
        params.put("floor_count",2);
        params.put("floor_height",3);
        var c = new Component("FACADE_WINDOWS","",new Vec3i(9,6,10),new Dimensions(5,1,5),List.of(),params);
        var slot = new Slot("",new Vec3i(0,0,0),GlobalConstraints.Facing.SOUTH,null,null,null);
        var patches = new FacadeWindowsGenerator().generate(new SemanticComponent("FACADE_WINDOWS",slot,c,null,null,null));
        assertEquals(20,patches.size());
        assertEquals(java.util.Set.of(6,7,8,9),patches.stream().map(BlockPatch::dy).collect(java.util.stream.Collectors.toSet()));
        assertEquals(java.util.Set.of(9,10,11,12,13),patches.stream().map(BlockPatch::dx).collect(java.util.stream.Collectors.toSet()));
        assertTrue(patches.stream().allMatch(p -> p.dz()==10));
    }
    @Test
    void singleTallHallDoesNotSplitWindowsAtUnusedFloorHeight() {
        var params = new HashMap<String,Object>();
        params.put("window_width",1);
        params.put("window_height",5);
        params.put("sill_height",3);
        params.put("floor_height",5);
        params.put("floor_count",1);
        var c = new Component("FACADE_WINDOWS","",new Vec3i(0,0,0),new Dimensions(23,1,12),List.of(),params);
        var slot = new Slot("",new Vec3i(0,0,0),GlobalConstraints.Facing.SOUTH,null,null,null);
        var patches = new FacadeWindowsGenerator().generate(new SemanticComponent("FACADE_WINDOWS",slot,c,null,null,null));
        assertFalse(patches.isEmpty());
        assertEquals(java.util.Set.of(3,4,5,6,7),
                patches.stream().map(BlockPatch::dy).collect(java.util.stream.Collectors.toSet()));
    }
    @Test
    void tallWindowKeepsItsHeightWhenSillWouldExceedStorey() {
        var params = new HashMap<String,Object>();
        params.put("window_width", 2);
        params.put("window_height", 6);
        params.put("sill_height", 3);
        params.put("floor_height", 8);
        params.put("floor_count", 2);
        var c = new Component("FACADE_WINDOWS", "", new Vec3i(0,0,0),
                new Dimensions(24,1,16), List.of(), params);
        var slot = new Slot("", new Vec3i(0,0,0), GlobalConstraints.Facing.SOUTH, null,null,null);
        var patches = new FacadeWindowsGenerator().generate(new SemanticComponent("FACADE_WINDOWS",slot,c,null,null,null));
        assertFalse(patches.isEmpty());
        assertEquals(java.util.Set.of(2,3,4,5,6,7,10,11,12,13,14,15),
                patches.stream().map(BlockPatch::dy).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void verticalStrip_producesNarrowAxisSpan() {
        List<BlockPatch> patches = generateWithAspect("vertical_strip", 20, 8, 1);
        assertFalse(patches.isEmpty());
        // vertical strips: limited z depth (facade depth=1), varied x
        long distinctX = patches.stream().map(BlockPatch::dx).distinct().count();
        long distinctZ = patches.stream().map(BlockPatch::dz).distinct().count();
        assertTrue(distinctX <= 8, "vertical strip should not span full facade width");
        assertTrue(distinctZ <= 1);
    }

    @Test
    void horizontalStrip_spansFacadeWidth() {
        List<BlockPatch> patches = generateWithAspect("horizontal_strip", 16, 6, 1);
        assertFalse(patches.isEmpty());
        long distinctX = patches.stream().map(BlockPatch::dx).distinct().count();
        assertTrue(distinctX >= 10, "horizontal strip should span most of facade width");
    }

    @Test
    void arrowSlit_sparseWindows() {
        List<BlockPatch> square = generateWithAspect("square", 20, 10, 1);
        List<BlockPatch> slit = generateWithAspect("arrow_slit", 20, 10, 1);
        assertTrue(slit.size() < square.size());
    }

    @Test
    void explicitSquare_notOverriddenByLargeFeature() {
        List<BlockPatch> explicitSquare = generateWithAspectAndFeatures(
                "square", List.of("large", "wide"), 20, 10, 1, 6);
        List<BlockPatch> inferredPunch = generateWithAspectAndFeatures(
                null, List.of("large", "wide"), 20, 10, 1, 6);
        assertFalse(explicitSquare.isEmpty());
        assertFalse(inferredPunch.isEmpty());
        // Punch uses a narrower mid-band; explicit square must not collapse to that geometry.
        assertTrue(explicitSquare.size() != inferredPunch.size()
                        || !sameYSpan(explicitSquare, inferredPunch),
                "explicit square must not become punch_window via large feature");
    }

    @Test
    void omittedAspect_largeFeatureInfersPunch() {
        List<BlockPatch> withLarge = generateWithAspectAndFeatures(
                null, List.of("large"), 20, 10, 1);
        List<BlockPatch> punch = generateWithAspect("punch_window", 20, 10, 1);
        assertEquals(punch.size(), withLarge.size());
    }

    private static boolean sameYSpan(List<BlockPatch> a, List<BlockPatch> b) {
        var ya = a.stream().map(BlockPatch::dy).collect(java.util.stream.Collectors.toSet());
        var yb = b.stream().map(BlockPatch::dy).collect(java.util.stream.Collectors.toSet());
        return ya.equals(yb);
    }

    private static List<BlockPatch> generateWithAspect(String aspect, int width, int height, int depth) {
        return generateWithAspectAndFeatures(aspect, List.of(), width, height, depth);
    }

    private static List<BlockPatch> generateWithAspectAndFeatures(
            String aspect, List<String> features, int width, int height, int depth) {
        return generateWithAspectAndFeatures(aspect, features, width, height, depth, 0);
    }

    private static List<BlockPatch> generateWithAspectAndFeatures(
            String aspect, List<String> features, int width, int height, int depth, int floorHeight) {
        Map<String, Object> params = new HashMap<>();
        if (floorHeight > 0) params.put("floor_height", floorHeight);
        if (aspect != null) {
            params.put("window_aspect", aspect);
        }
        params.put("window_ratio", 0.15);
        Component c = new Component(
                "FACADE_WINDOWS",
                "",
                new Vec3i(0, 0, 0),
                new Dimensions(width, depth, height),
                features,
                params
        );
        Slot slot = new Slot("", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        SemanticComponent semantic = new SemanticComponent("FACADE_WINDOWS", slot, c, null, null, null);
        return new FacadeWindowsGenerator().generate(semantic);
    }
}
