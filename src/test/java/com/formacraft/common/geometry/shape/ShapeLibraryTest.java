package com.formacraft.common.geometry.shape;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeLibraryTest {

    @Test
    void box_fillsBoundingVolume() {
        ShapeSpec spec = ShapeSpec.fromParams(4, 3, 5, Map.of("kind", "box"));
        List<ShapeLibrary.Voxel> voxels = ShapeLibrary.generate(spec);
        assertEquals(4 * 3 * 5, voxels.size());
    }

    @Test
    void cylinder_smallerThanBox() {
        ShapeSpec spec = ShapeSpec.fromParams(10, 10, 8, Map.of("kind", "cylinder", "radius", 4));
        List<ShapeLibrary.Voxel> voxels = ShapeLibrary.generate(spec);
        assertTrue(voxels.size() > 0);
        assertTrue(voxels.size() < 10 * 10 * 8);
    }

    @Test
    void hollowCylinder_hasCavity() {
        ShapeSpec solid = ShapeSpec.fromParams(10, 10, 6, Map.of("kind", "cylinder", "radius", 4, "hollow", false));
        ShapeSpec hollow = ShapeSpec.fromParams(10, 10, 6, Map.of("kind", "cylinder", "radius", 4, "hollow", true, "thickness", 1));
        assertTrue(ShapeLibrary.generate(hollow).size() < ShapeLibrary.generate(solid).size());
    }

    @Test
    void cone_tapersTowardTop() {
        ShapeSpec spec = ShapeSpec.fromParams(11, 11, 10, Map.of("kind", "cone", "radius", 5));
        Set<Integer> countsByY = new HashSet<>();
        int[] perY = new int[spec.height()];
        for (ShapeLibrary.Voxel v : ShapeLibrary.generate(spec)) {
            perY[v.y()]++;
        }
        assertTrue(perY[0] > perY[spec.height() - 1]);
    }

    @Test
    void prism_hexagon() {
        ShapeSpec spec = ShapeSpec.fromParams(12, 12, 4, Map.of("kind", "prism", "sides", 6, "radius", 5));
        List<ShapeLibrary.Voxel> voxels = ShapeLibrary.generate(spec);
        assertTrue(voxels.size() > 0);
        assertTrue(voxels.size() < 12 * 12 * 4);
    }

    @Test
    void primitiveRegistered() {
        assertTrue(com.formacraft.common.generation.component.ComponentGeneratorRegistry.hasGenerator("PRIMITIVE"));
        assertTrue(com.formacraft.common.generation.component.ComponentGeneratorRegistry.hasGenerator("SHAPE"));
    }
}
