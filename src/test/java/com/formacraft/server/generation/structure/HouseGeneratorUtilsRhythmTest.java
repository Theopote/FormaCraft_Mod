package com.formacraft.server.generation.structure;

import com.formacraft.common.facade.rhythm.RepeatingPattern;
import com.formacraft.common.generation.component.util.ComponentFacadeRhythmPlanner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HouseGeneratorUtilsRhythmTest {

    @Test
    void pilasterAxesMatchClassicalRepeatingUnit() {
        ComponentFacadeRhythmPlanner.RhythmPlan plan =
                ComponentFacadeRhythmPlanner.fromRepeatingPattern(RepeatingPattern.classicalPilasterBay(), 13);

        assertTrue(plan.active());
        assertTrue(HouseGeneratorUtils.isRhythmPilasterExteriorCell(plan, plan, 4, 2, 0, 13, 10));
        assertFalse(HouseGeneratorUtils.isRhythmPilasterExteriorCell(plan, plan, 6, 2, 0, 13, 10));
        assertFalse(HouseGeneratorUtils.isRhythmPilasterExteriorCell(plan, plan, 4, 0, 0, 13, 10));
    }

    @Test
    void depthFacadeUsesDepthRhythmPlan() {
        ComponentFacadeRhythmPlanner.RhythmPlan width =
                ComponentFacadeRhythmPlanner.fromRepeatingPattern(RepeatingPattern.classicalPilasterBay(), 13);
        ComponentFacadeRhythmPlanner.RhythmPlan depth =
                ComponentFacadeRhythmPlanner.fromRepeatingPattern(RepeatingPattern.classicalPilasterBay(), 10);

        assertTrue(HouseGeneratorUtils.isRhythmPilasterExteriorCell(width, depth, 0, 2, 4, 13, 10));
        assertFalse(HouseGeneratorUtils.isRhythmPilasterExteriorCell(width, depth, 5, 2, 4, 13, 10));
    }
}
