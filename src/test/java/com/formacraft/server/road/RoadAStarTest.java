package com.formacraft.server.road;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadAStarTest {

    @Test
    void steeperStepCostsStrictlyMoreThanSingleStep() {
        int stepPenalty = 12;
        int flat = RoadAStar.moveCost(RoadSurface.GROUND, 0, 0, stepPenalty, 0, 0);
        int one = RoadAStar.moveCost(RoadSurface.GROUND, 1, 0, stepPenalty, 0, 0);
        int two = RoadAStar.moveCost(RoadSurface.GROUND, 2, 0, stepPenalty, 0, 0);
        assertEquals(1, flat);
        assertEquals(1 + stepPenalty, one);
        assertEquals(1 + 2 * stepPenalty, two);
        assertTrue(two > one, "2-block rise must cost more than 1-block rise");
    }

    @Test
    void negativeDyAlsoPaysStepPenalty() {
        int stepPenalty = 12;
        int down2 = RoadAStar.moveCost(RoadSurface.GROUND, -2, 0, stepPenalty, 0, 0);
        assertEquals(1 + 2 * stepPenalty, down2);
    }

    @Test
    void bridgePenaltyStacksWithStepPenalty() {
        int cost = RoadAStar.moveCost(RoadSurface.BRIDGE, 2, 0, 10, 0, 6);
        assertEquals(1 + 6 + 20, cost);
    }
}
