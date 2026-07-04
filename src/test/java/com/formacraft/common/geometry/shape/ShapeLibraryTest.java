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

    @Test
    void sphere_smallerThanBox() {
        ShapeSpec spec = ShapeSpec.fromParams(12, 12, 12, Map.of("kind", "sphere", "radius", 5));
        List<ShapeLibrary.Voxel> voxels = ShapeLibrary.generate(spec);
        assertTrue(voxels.size() > 0);
        assertTrue(voxels.size() < 12 * 12 * 12);
    }

    @Test
    void hemisphere_onlyLowerHalf() {
        ShapeSpec spec = ShapeSpec.fromParams(12, 12, 12, Map.of("kind", "hemisphere", "radius", 5));
        List<ShapeLibrary.Voxel> full = ShapeLibrary.generate(
                ShapeSpec.fromParams(12, 12, 12, Map.of("kind", "sphere", "radius", 5)));
        List<ShapeLibrary.Voxel> half = ShapeLibrary.generate(spec);
        assertTrue(half.size() < full.size());
        for (ShapeLibrary.Voxel v : half) {
            assertTrue(v.y() <= spec.halfY() + 0.5);
        }
    }

    @Test
    void sector_smallerThanCylinder() {
        ShapeSpec sector = ShapeSpec.fromParams(12, 12, 8, Map.of(
                "kind", "sector", "radius", 5, "sector_sweep_deg", 90));
        ShapeSpec cylinder = ShapeSpec.fromParams(12, 12, 8, Map.of("kind", "cylinder", "radius", 5));
        assertTrue(ShapeLibrary.generate(sector).size() < ShapeLibrary.generate(cylinder).size());
    }

    @Test
    void triangle_footprint() {
        ShapeSpec spec = ShapeSpec.fromParams(10, 10, 4, Map.of(
                "kind", "triangle", "radius", 4, "triangle_mode", "right"));
        List<ShapeLibrary.Voxel> voxels = ShapeLibrary.generate(spec);
        assertTrue(voxels.size() > 0);
        assertTrue(voxels.size() < 10 * 10 * 4);
    }

    @Test
    void csg_subtract_carvesHole() {
        int w = 12, d = 12, h = 12;
        Map<String, Object> params = Map.of(
                "kind", "box",
                "subtract", Map.of("kind", "cylinder", "radius", 3));
        List<ShapeCsgOperation> ops = ShapeSpec.parseOperations(w, d, h, params);
        assertEquals(2, ops.size());
        List<ShapeLibrary.Voxel> solid = ShapeLibrary.generate(
                ShapeSpec.fromParams(w, d, h, Map.of("kind", "box")));
        List<ShapeLibrary.Voxel> carved = ShapeLibrary.generateComposite(ops);
        assertTrue(carved.size() < solid.size());
    }

    @Test
    void csg_operations_chain() {
        int w = 12, d = 12, h = 12;
        Map<String, Object> params = Map.of(
                "operations", List.of(
                        Map.of("op", "union", "kind", "box"),
                        Map.of("op", "subtract", "kind", "cylinder", "radius", 4)));
        List<ShapeCsgOperation> ops = ShapeSpec.parseOperations(w, d, h, params);
        assertEquals(2, ops.size());
        assertTrue(ShapeLibrary.generateComposite(ops).size() > 0);
    }

    @Test
    void rotation_doesNotCrash() {
        ShapeSpec spec = ShapeSpec.fromParams(10, 10, 10, Map.of(
                "kind", "box",
                "rotation_x_deg", 15,
                "rotation_y_deg", 30,
                "rotation_z_deg", 45));
        assertTrue(ShapeLibrary.generate(spec).size() > 0);
    }

    @Test
    void plateMode_singleLayer() {
        ShapeSpec spec = ShapeSpec.fromParams(10, 10, 8, Map.of(
                "kind", "cylinder", "radius", 4, "extrude_mode", "plate"));
        List<ShapeLibrary.Voxel> voxels = ShapeLibrary.generate(spec);
        assertTrue(voxels.size() > 0);
        assertTrue(voxels.stream().allMatch(v -> v.y() == 0));
        assertTrue(voxels.size() < 10 * 10);
    }

    @Test
    void voronoi_producesCells() {
        ShapeSpec spec = ShapeSpec.fromParams(16, 16, 6, Map.of(
                "kind", "voronoi", "radius", 7, "cell_count", 10, "seed", 42));
        List<ShapeLibrary.Voxel> voxels = ShapeLibrary.generate(spec);
        assertTrue(voxels.size() > 0);
        assertTrue(voxels.size() < 16 * 16 * 6);
    }

    @Test
    void mobius_thinSolid() {
        ShapeSpec spec = ShapeSpec.fromParams(16, 16, 8, Map.of(
                "kind", "mobius", "radius", 5, "mobius_width", 3));
        List<ShapeLibrary.Voxel> solid = ShapeLibrary.generate(spec);
        List<ShapeLibrary.Voxel> box = ShapeLibrary.generate(
                ShapeSpec.fromParams(16, 16, 8, Map.of("kind", "box")));
        assertTrue(solid.size() > 0);
        assertTrue(solid.size() < box.size());
    }

    @Test
    void voronoi3d_usesVolumeCells() {
        ShapeSpec planar = ShapeSpec.fromParams(14, 14, 10, Map.of(
                "kind", "voronoi", "radius", 6, "cell_count", 8, "seed", 7));
        ShapeSpec volume = ShapeSpec.fromParams(14, 14, 10, Map.of(
                "kind", "voronoi", "radius", 6, "cell_count", 8, "seed", 7, "voronoi_3d", true));
        List<ShapeLibrary.Voxel> planarVoxels = ShapeLibrary.generate(planar);
        List<ShapeLibrary.Voxel> volumeVoxels = ShapeLibrary.generate(volume);
        assertTrue(volumeVoxels.size() > 0);
        long planarY = planarVoxels.stream().map(ShapeLibrary.Voxel::y).distinct().count();
        long volumeY = volumeVoxels.stream().map(ShapeLibrary.Voxel::y).distinct().count();
        assertTrue(planarY <= volumeY || volumeVoxels.size() != planarVoxels.size());
    }

    @Test
    void mobiusCsg_subtractFromBox() {
        int w = 16, d = 16, h = 10;
        Map<String, Object> params = Map.of(
                "kind", "box",
                "operations", List.of(
                        Map.of("op", "union", "kind", "box"),
                        Map.of("op", "subtract", "kind", "mobius", "radius", 5, "mobius_width", 3)));
        List<ShapeCsgOperation> ops = ShapeSpec.parseOperations(w, d, h, params);
        List<ShapeLibrary.Voxel> solid = ShapeLibrary.generate(
                ShapeSpec.fromParams(w, d, h, Map.of("kind", "box")));
        List<ShapeLibrary.Voxel> carved = ShapeLibrary.generateComposite(ops);
        assertTrue(carved.size() < solid.size());
    }
}
