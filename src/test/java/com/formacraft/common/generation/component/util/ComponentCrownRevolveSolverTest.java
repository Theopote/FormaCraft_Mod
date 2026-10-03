package com.formacraft.common.generation.component.util;

import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentCrownRevolveSolverTest {

    @Test
    void emitRevolveSolid_buildsVerticalStackWithDecreasingRadius() {
        List<BlockPatch> patches = new ArrayList<>();
        ComponentCrownRevolveSolver.emitRevolveSolid(
                patches,
                10,
                20,
                10,
                4.0,
                6,
                CrownTemplateLibrary.profile(CrownTemplateLibrary.SIMPLE_DOME),
                "minecraft:quartz_block",
                24
        );
        assertFalse(patches.isEmpty());
        assertTrue(patches.stream().anyMatch(p -> p.dy() == 20));
        assertTrue(patches.stream().anyMatch(p -> p.dy() == 25));
        int lowCount = (int) patches.stream().filter(p -> p.dy() == 20).count();
        int highCount = (int) patches.stream().filter(p -> p.dy() == 25).count();
        assertTrue(lowCount > highCount);
        assertEquals(1, highCount);
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 10 && p.dy() == 25 && p.dz() == 10));
        assertTrue(patches.stream().allMatch(p -> p.dy() >= 20 && p.dy() < 26));
        assertEquals(6, patches.stream().map(BlockPatch::dy).distinct().count());
    }

    @Test
    void singleLayerUsesBaseProfileAndDoesNotAddAnExtraLayer() {
        List<BlockPatch> patches = new ArrayList<>();
        ComponentCrownRevolveSolver.emitRevolveSolid(patches, -10, -20, -30, 2, 1,
                List.of(new double[]{1, 0}, new double[]{0, 1}), "minecraft:quartz_block", 24);
        assertTrue(patches.stream().anyMatch(p -> p.dx() == -12 && p.dz() == -30));
        assertTrue(patches.stream().allMatch(p -> p.dy() == -20));
    }

    @Test
    void zeroRadiusProducesCentralSpineAndInvalidHeightProducesNothing() {
        List<BlockPatch> patches = new ArrayList<>();
        var profile = List.of(new double[]{0, 0}, new double[]{0, 1});
        ComponentCrownRevolveSolver.emitRevolveSolid(patches, -2, -4, -6, 3, 3,
                profile, "minecraft:quartz_block", 24);
        assertEquals(3, patches.size());
        assertTrue(patches.stream().allMatch(p -> p.dx() == -2 && p.dz() == -6));
        ComponentCrownRevolveSolver.emitRevolveSolid(patches, 0, 0, 0, 3, 0,
                profile, "minecraft:quartz_block", 24);
        assertEquals(3, patches.size());
    }

    @Test
    void interpolateRadius_returnsOuterValueAtBase() {
        double r = ComponentCrownRevolveSolver.interpolateRadius(
                CrownTemplateLibrary.profile(CrownTemplateLibrary.CLASSICAL_CUPOLA),
                0.0
        );
        assertTrue(r > 0.35);
    }
}
