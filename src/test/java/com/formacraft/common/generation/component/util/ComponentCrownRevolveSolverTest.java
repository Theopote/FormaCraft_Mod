package com.formacraft.common.generation.component.util;

import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
        assertTrue(patches.stream().anyMatch(p -> p.dy() == 26));
        int lowCount = (int) patches.stream().filter(p -> p.dy() == 20).count();
        int highCount = (int) patches.stream().filter(p -> p.dy() == 26).count();
        assertTrue(lowCount > highCount);
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
