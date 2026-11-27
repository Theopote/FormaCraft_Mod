package com.formacraft.common.generation.component.util;

import com.formacraft.common.geometry.shape.CsgOp;
import com.formacraft.common.geometry.shape.ShapeLibrary;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MassBooleanCompositionTest {

    @Test
    void nestedAtriumSubtractClearsInnerCourt() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "atrium_hall");
        params.put("anchor_mode", "min_corner");
        params.put("masses", List.of(
                Map.of("operation", "add",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 10, "depth", 10, "height", 6)),
                Map.of("operation", "subtract",
                        "offset", Map.of("x", 2, "y", 0, "z", 2),
                        "dimensions", Map.of("width", 6, "depth", 6, "height", 6))
        ));
        Component c = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 6), List.of(), params);
        assertTrue(MassBooleanComposition.needsCsg(c));
        var result = MassBooleanComposition.composeFromComponent(c);
        Set<String> keys = result.voxels().stream()
                .map(v -> v.x() + "," + v.y() + "," + v.z())
                .collect(Collectors.toSet());
        assertFalse(keys.contains("5,1,5"), "court center must be carved");
        assertTrue(keys.contains("0,0,0"), "outer shell remains");
        assertTrue(keys.contains("9,5,9"), "outer far corner remains");
    }

    @Test
    void nestedIntersectKeepsOnlyOverlap() {
        Map<String, Object> params = new HashMap<>();
        params.put("anchor_mode", "min_corner");
        params.put("masses", List.of(
                Map.of("operation", "add",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 4)),
                Map.of("operation", "intersect",
                        "offset", Map.of("x", 4, "y", 0, "z", 4),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 4))
        ));
        Component c = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 4), List.of(), params);
        var result = MassBooleanComposition.composeFromComponent(c);
        for (ShapeLibrary.Voxel v : result.voxels()) {
            assertTrue(v.x() >= 4 && v.x() < 8);
            assertTrue(v.z() >= 4 && v.z() < 8);
        }
        assertFalse(result.voxels().isEmpty());
    }

    @Test
    void absentOperationDoesNotForceCsg() {
        Map<String, Object> params = new HashMap<>();
        params.put("masses", List.of(
                Map.of("offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 6, "depth", 6, "height", 4)),
                Map.of("offset", Map.of("x", 0, "y", 4, "z", 0),
                        "dimensions", Map.of("width", 4, "depth", 4, "height", 4))
        ));
        Component c = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(6, 6, 8), List.of(), params);
        assertFalse(MassBooleanComposition.needsCsg(c));
    }

    @Test
    void parseAddAsUnion() {
        assertEquals(CsgOp.UNION, MassBooleanComposition.parseOpLenient("add"));
        assertEquals(CsgOp.SUBTRACT, MassBooleanComposition.parseOpLenient("carve"));
        assertEquals(CsgOp.INTERSECT, MassBooleanComposition.parseOpLenient("intersect"));
    }

    @Test
    void indexedContainsAndCarveVoidsNotAabbBlast() {
        var result = MassBooleanComposition.compose(List.of(
                new MassBooleanComposition.CsgPart(CsgOp.UNION, 0, 0, 0, 6, 6, 4),
                new MassBooleanComposition.CsgPart(CsgOp.SUBTRACT, 2, 0, 2, 2, 2, 4)
        ));
        assertTrue(result.contains(0, 0, 0));
        assertFalse(result.contains(2, 1, 2));
        assertFalse(result.carveVoids().isEmpty());
        // Sparse far UNION must not explode voids to AABB volume.
        var sparse = MassBooleanComposition.compose(List.of(
                new MassBooleanComposition.CsgPart(CsgOp.UNION, 0, 0, 0, 2, 2, 2),
                new MassBooleanComposition.CsgPart(CsgOp.UNION, 200, 0, 200, 2, 2, 2)
        ));
        assertTrue(sparse.carveVoids().isEmpty());
        assertEquals(2 * 2 * 2 * 2, sparse.voxels().size());
    }

    @Test
    void shellWallsErodesInterior() {
        var solid = MassBooleanComposition.compose(List.of(
                new MassBooleanComposition.CsgPart(CsgOp.UNION, 0, 0, 0, 8, 8, 4)
        ));
        var shell = solid.shellWalls(1);
        assertFalse(shell.isEmpty());
        assertTrue(shell.size() < solid.voxels().size());
        Set<String> keys = shell.stream().map(v -> v.x() + "," + v.y() + "," + v.z()).collect(Collectors.toSet());
        assertFalse(keys.contains("3,1,3"), "eroded interior absent from shell");
        assertTrue(keys.contains("0,0,0"));
    }

    @Test
    void hostSphereGeometryRejectedForCsg() {
        Map<String, Object> params = new HashMap<>();
        params.put("volume_geometry", "sphere");
        params.put("masses", List.of(
                Map.of("operation", "add",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 6, "depth", 6, "height", 6)),
                Map.of("operation", "subtract",
                        "offset", Map.of("x", 2, "y", 2, "z", 2),
                        "dimensions", Map.of("width", 2, "depth", 2, "height", 2))
        ));
        Component c = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(6, 6, 6), List.of(), params);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MassBooleanComposition.composeFromComponent(c));
        assertTrue(ex.getMessage().startsWith("E_MASS_CSG_SHAPE_UNSUPPORTED"));
    }
}
