package com.formacraft.server.generation.structure.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChineseLandmarkDetailUtilTest {

    @Test
    void regularOctagon_isMoreCompactThanSquare() {
        int half = 5;
        int square = 0;
        int oct = 0;
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                square++;
                if (ChineseLandmarkDetailUtil.inRegularOctagon(x, z, half)) {
                    oct++;
                }
            }
        }
        assertTrue(oct < square);
        assertTrue(oct >= square * 0.75, "octagon should retain most of the square for half=5");
    }

    @Test
    void regularOctagon_cornersAreCut() {
        int half = 5;
        assertFalse(ChineseLandmarkDetailUtil.inRegularOctagon(half, half, half));
        assertTrue(ChineseLandmarkDetailUtil.inRegularOctagon(half, 0, half));
        assertTrue(ChineseLandmarkDetailUtil.inRegularOctagon(0, half, half));
    }

    @Test
    void octagonFaceCell_onCardinalAxis() {
        assertEquals(5, ChineseLandmarkDetailUtil.octagonFaceCell(5, net.minecraft.util.math.Direction.SOUTH).getZ());
        assertEquals(-5, ChineseLandmarkDetailUtil.octagonFaceCell(5, net.minecraft.util.math.Direction.NORTH).getZ());
    }

    @Test
    void perimeterColumns_includesCorners() {
        var cols = ChineseLandmarkDetailUtil.perimeterColumnPositions(0, 0, 21, 15, 3);
        assertFalse(cols.isEmpty());
        assertTrue(cols.stream().anyMatch(c -> c[0] == 0 && c[1] == 0));
        assertTrue(cols.stream().anyMatch(c -> c[0] == 21 && c[1] == 15));
    }
}
