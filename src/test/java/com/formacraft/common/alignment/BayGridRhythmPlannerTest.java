package com.formacraft.common.alignment;

import com.formacraft.common.facade.rhythm.RepeatingPattern;
import com.formacraft.common.generation.component.util.ComponentFacadeRhythmPlanner;
import com.formacraft.common.llm.dto.GlobalConstraints;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BayGridRhythmPlannerTest {

    @Test
    void toRhythmPlan_marksWingWindowsAndCenterEntranceBay() {
        BayGridResolver.ResolvedAxisGrid grid = new BayGridResolver.ResolvedAxisGrid(
                14,
                List.of(
                        new BayGridResolver.BaySpan(0, 4, "wing"),
                        new BayGridResolver.BaySpan(4, 6, "hall"),
                        new BayGridResolver.BaySpan(10, 4, "wing")
                )
        );

        ComponentFacadeRhythmPlanner.RhythmPlan plan = BayGridRhythmPlanner.toRhythmPlan(grid, 14);
        assertTrue(plan.active());
        assertEquals("BAY_GRID", plan.presetId());
        assertTrue(plan.isEntranceBayAxis(5));
        assertTrue(plan.isEntranceBayAxis(8));
        assertFalse(plan.isWindowAxis(5));
        assertTrue(plan.isWindowAxis(1));
        assertTrue(plan.isWindowAxis(2));
        assertTrue(plan.isWindowAxis(11));
        assertTrue(plan.isPilasterAxis(0));
        assertTrue(plan.isPilasterAxis(4));
        assertTrue(plan.isPilasterAxis(9));
    }

    @Test
    void snapEntrance_usesHallBayOnSouthFacade() {
        Map<String, Object> params = Map.of(
                "bay_grid_x", Map.of(
                        "total_span", 14,
                        "bays", List.of(
                                Map.of("start", 0, "width", 4, "role", "wing"),
                                Map.of("start", 4, "width", 6, "role", "hall"),
                                Map.of("start", 10, "width", 4, "role", "wing")
                        )
                )
        );

        BayGridRhythmPlanner.EntranceSnap snap = BayGridRhythmPlanner.snapEntrance(
                params, 14, 10, GlobalConstraints.Facing.SOUTH);
        assertNotNull(snap);
        assertEquals(4, snap.axisStart());
        assertEquals(6, snap.axisSpan());
    }

    @Test
    void pickGrid_matchesAxisSpanFromEitherAxis() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bay_grid_z", Map.of(
                "total_span", 11,
                "bays", List.of(
                        Map.of("start", 0, "width", 3, "role", "service"),
                        Map.of("start", 3, "width", 5, "role", "hall"),
                        Map.of("start", 8, "width", 3, "role", "service")
                )
        ));

        assertNotNull(BayGridRhythmPlanner.pickGrid(params, 11));
        assertNotNull(BayGridRhythmPlanner.pickFacadeGrid(
                params, 20, 11, GlobalConstraints.Facing.EAST));
    }

    @Test
    void toRhythmPlan_tilesRepeatingPatternWithinEachBay() {
        BayGridResolver.ResolvedAxisGrid grid = new BayGridResolver.ResolvedAxisGrid(
                14,
                List.of(
                        new BayGridResolver.BaySpan(0, 4, "wing"),
                        new BayGridResolver.BaySpan(4, 6, "hall"),
                        new BayGridResolver.BaySpan(10, 4, "wing")
                )
        );

        ComponentFacadeRhythmPlanner.RhythmPlan plan = BayGridRhythmPlanner.toRhythmPlan(
                grid, 14, RepeatingPattern.classicalPilasterBay());

        assertEquals("BAY_GRID+REPEATING_PATTERN", plan.presetId());
        assertTrue(plan.isEntranceBayAxis(6));
        assertTrue(plan.isEntranceBayAxis(7));
        assertTrue(plan.isEntranceBayAxis(8));
        assertFalse(plan.isWindowAxis(6));
        assertTrue(plan.isWindowAxis(1));
        assertTrue(plan.isWindowAxis(2));
        assertTrue(plan.isPilasterAxis(4));
        assertTrue(plan.isPilasterAxis(9));
    }

    @Test
    void findEntranceBay_prefersExplicitEntranceRole() {
        BayGridResolver.ResolvedAxisGrid grid = new BayGridResolver.ResolvedAxisGrid(
                12,
                List.of(
                        new BayGridResolver.BaySpan(0, 4, "wing"),
                        new BayGridResolver.BaySpan(4, 4, "entrance"),
                        new BayGridResolver.BaySpan(8, 4, "wing")
                )
        );
        BayGridRhythmPlanner.EntranceBay bay = BayGridRhythmPlanner.findEntranceBay(grid);
        assertEquals(4, bay.start());
        assertEquals(4, bay.width());
    }
}
