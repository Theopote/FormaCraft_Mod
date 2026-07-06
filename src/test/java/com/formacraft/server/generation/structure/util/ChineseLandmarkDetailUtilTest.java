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
    void octagonFaceByIndex_cyclesEightFaces() {
        var f0 = ChineseLandmarkDetailUtil.octagonFaceByIndex(5, 0);
        var f8 = ChineseLandmarkDetailUtil.octagonFaceByIndex(5, 8);
        assertEquals(f0.x(), f8.x());
        assertEquals(f0.z(), f8.z());
        assertNotEquals(
                ChineseLandmarkDetailUtil.octagonFaceByIndex(5, 0).x(),
                ChineseLandmarkDetailUtil.octagonFaceByIndex(5, 2).x()
        );
    }

    @Test
    void resolvePuzuoProfile_cornerVsEdge() {
        assertEquals(
                ChineseLandmarkDetailUtil.PuzuoProfile.INTERIOR_CORNER,
                ChineseLandmarkDetailUtil.resolvePuzuoProfile(false, true)
        );
        assertEquals(
                ChineseLandmarkDetailUtil.PuzuoProfile.SUB_EAVES_EDGE,
                ChineseLandmarkDetailUtil.resolvePuzuoProfile(true, false)
        );
    }

    @Test
    void isCornerColumn_detectsRectangleCorners() {
        assertTrue(ChineseLandmarkDetailUtil.isCornerColumn(0, 0, 0, 0, 21, 15));
        assertFalse(ChineseLandmarkDetailUtil.isCornerColumn(9, 0, 0, 0, 21, 15));
    }
}
