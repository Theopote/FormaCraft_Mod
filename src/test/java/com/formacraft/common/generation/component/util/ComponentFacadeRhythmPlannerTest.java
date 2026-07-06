package com.formacraft.common.generation.component.util;

import com.formacraft.common.facade.rhythm.RepeatingPattern;
import com.formacraft.common.facade.rhythm.RepeatingPatternParser;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentFacadeRhythmPlannerTest {

    @Test
    void classicalPilasterBay_width13_centeredSymmetric() {
        BitSet axes = ComponentFacadeRhythmPlanner.computeClassicalPilasterBayAxes(13);
        assertTrue(axes.get(1));
        assertTrue(axes.get(2));
        assertTrue(axes.get(5));
        assertTrue(axes.get(6));
        assertTrue(axes.get(7));
        assertTrue(axes.get(10));
        assertTrue(axes.get(11));
        assertFalse(axes.get(0));
        assertFalse(axes.get(12));
        assertSymmetric(axes, 13);
    }

    @Test
    void classicalPilasterBay_width17_multipleBays() {
        BitSet axes = ComponentFacadeRhythmPlanner.computeClassicalPilasterBayAxes(17);
        assertTrue(axes.get(2));
        assertTrue(axes.get(3));
        assertTrue(axes.get(4));
        assertTrue(axes.get(7));
        assertTrue(axes.get(8));
        assertTrue(axes.get(9));
        assertTrue(axes.get(12));
        assertTrue(axes.get(13));
        assertTrue(axes.get(14));
        assertSymmetric(axes, 17);
    }

    @Test
    void classicalPilasterAxes_doNotOverlapWindows() {
        BitSet windows = ComponentFacadeRhythmPlanner.computeClassicalPilasterBayAxes(13);
        BitSet pilasters = ComponentFacadeRhythmPlanner.computeClassicalPilasterAxes(13);
        for (int i = 0; i < 13; i++) {
            assertFalse(windows.get(i) && pilasters.get(i), "axis " + i + " cannot be both window and pilaster");
        }
        assertTrue(pilasters.get(3));
        assertTrue(pilasters.get(4));
        assertTrue(pilasters.get(8));
        assertTrue(pilasters.get(9));
    }

    @Test
    void classicalEntranceBay_isCenterWindows() {
        BitSet entrance = ComponentFacadeRhythmPlanner.computeClassicalEntranceBayWindowAxes(13);
        assertTrue(entrance.get(5));
        assertTrue(entrance.get(6));
        assertTrue(entrance.get(7));
    }

    @Test
    void resolveUsesExplicitRepeatingPattern() {
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("repeating_pattern", RepeatingPatternParser.toParamsMap(RepeatingPattern.classicalPilasterBay()));
        ComponentFacadeRhythmPlanner.RhythmPlan plan = ComponentFacadeRhythmPlanner.resolve(null, params, 13);
        assertTrue(plan.active());
        assertTrue(plan.isWindowAxis(6));
    }

    @Test
    void resolvePrefersBayGridOverRepeatingPattern() {
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("repeating_pattern", RepeatingPatternParser.toParamsMap(RepeatingPattern.classicalPilasterBay()));
        params.put("bay_grid_x", java.util.Map.of(
                "total_span", 14,
                "bays", java.util.List.of(
                        java.util.Map.of("start", 0, "width", 4, "role", "wing"),
                        java.util.Map.of("start", 4, "width", 6, "role", "hall"),
                        java.util.Map.of("start", 10, "width", 4, "role", "wing")
                )
        ));
        ComponentFacadeRhythmPlanner.RhythmPlan plan = ComponentFacadeRhythmPlanner.resolve(null, params, 14);
        assertTrue(plan.active());
        assertEquals("BAY_GRID", plan.presetId());
        assertTrue(plan.isEntranceBayAxis(6));
    }

    @Test
    void regularBilateral_spacing3() {
        BitSet axes = ComponentFacadeRhythmPlanner.computeRegularBilateralAxes(13, 3);
        assertTrue(axes.get(6));
        assertTrue(axes.get(3));
        assertTrue(axes.get(9));
        assertSymmetric(axes, 13);
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
