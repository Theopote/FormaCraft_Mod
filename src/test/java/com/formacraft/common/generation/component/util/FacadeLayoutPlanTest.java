package com.formacraft.common.generation.component.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FacadeLayoutPlanTest {
    @Test
    void rebalancesAcrossSidesWhenHalfDoesNotFit() {
        // reserve=4 yields leftCap=2, rightCap=3; naive half of 5 is 3+2 → overflows left → 2+3.
        var plan = FacadeLayoutPlan.solve(21, 2, 5, -1, true, 4, 1, true);
        assertEquals(5, plan.windowCount());
        int left = (int) plan.windowStarts().stream().filter(s -> s < plan.entranceStart()).count();
        int right = (int) plan.windowStarts().stream().filter(s -> s >= plan.entranceEnd()).count();
        assertEquals(2, left);
        assertEquals(3, right);
    }

    @Test
    void trulyInsufficientCapacityFails() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> FacadeLayoutPlan.solve(12, 2, 20, -1, true, 3, 1, true));
        assertTrue(ex.getMessage().startsWith("E_WINDOW_COUNT_UNFIT"));
    }

    @Test
    void countPerSideIsStrictPerSide() {
        // Small left interval cannot hold 3 windows of width 2
        var ex = assertThrows(IllegalArgumentException.class,
                () -> FacadeLayoutPlan.solve(14, 2, -1, 3, true, 5, 1, true));
        assertTrue(ex.getMessage().startsWith("E_WINDOW_COUNT_UNFIT"));
    }

    @Test
    void withoutEntrancePlacesOnFullSpan() {
        var plan = FacadeLayoutPlan.solve(15, 2, 3, -1, false, 0, 1, true);
        assertEquals(3, plan.windowCount());
        assertEquals(-1, plan.entranceStart());
    }
}
