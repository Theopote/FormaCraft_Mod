package com.formacraft.common.facade.rhythm;

import com.formacraft.common.generation.component.util.ComponentFacadeRhythmPlanner;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepeatingPatternTilerTest {

    @Test
    void classicalPatternMatchesLegacyWidth13() {
        RepeatingPattern pattern = RepeatingPattern.classicalPilasterBay();
        ComponentFacadeRhythmPlanner.RhythmPlan plan =
                ComponentFacadeRhythmPlanner.fromRepeatingPattern(pattern, 13, RepeatingPattern.PRESET_CLASSICAL_PILASTER_BAY);

        assertTrue(plan.active());
        assertTrue(plan.isWindowAxis(1));
        assertTrue(plan.isWindowAxis(2));
        assertTrue(plan.isWindowAxis(5));
        assertTrue(plan.isWindowAxis(6));
        assertTrue(plan.isWindowAxis(7));
        assertTrue(plan.isWindowAxis(10));
        assertTrue(plan.isWindowAxis(11));
        assertFalse(plan.isWindowAxis(0));
        assertFalse(plan.isWindowAxis(12));
        assertSymmetric(plan.windowAxes(), 13);
    }

    @Test
    void windowWithInsetNarrowsOpening() {
        RepeatingPattern pattern = new RepeatingPattern(
                5,
                java.util.List.of(
                        new RepeatingPatternElement(RepeatingPatternElement.Type.PILLAR, 1, 0),
                        new RepeatingPatternElement(RepeatingPatternElement.Type.WINDOW, 3, 1),
                        new RepeatingPatternElement(RepeatingPatternElement.Type.PILLAR, 1, 0)
                )
        );
        RepeatingPatternTiler.TiledAxes tiled = RepeatingPatternTiler.tile(pattern, 13);
        assertTrue(tiled.windowAxes().get(6));
        assertFalse(tiled.windowAxes().get(5));
        assertFalse(tiled.windowAxes().get(7));
    }

    private static void assertSymmetric(BitSet axes, int axisMax) {
        for (int i = 1; i < axisMax - 1; i++) {
            if (axes.get(i)) {
                int mirror = axisMax - 1 - i;
                assertTrue(axes.get(mirror), "axis " + i + " should mirror to " + mirror);
            }
        }
    }
}
