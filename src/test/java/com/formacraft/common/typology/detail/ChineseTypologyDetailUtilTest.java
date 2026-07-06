package com.formacraft.common.typology.detail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChineseTypologyDetailUtilTest {

    @Test
    void regularOctagon_isMoreCompactThanSquare() {
        int half = 5;
        int square = 0;
        int oct = 0;
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                square++;
                if (ChineseTypologyDetailUtil.inRegularOctagon(x, z, half)) {
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
        assertFalse(ChineseTypologyDetailUtil.inRegularOctagon(half, half, half));
        assertTrue(ChineseTypologyDetailUtil.inRegularOctagon(half, 0, half));
        assertTrue(ChineseTypologyDetailUtil.inRegularOctagon(0, half, half));
    }

    @Test
    void octagonFaceCell_onCardinalAxis() {
        assertEquals(5, ChineseTypologyDetailUtil.octagonFaceCell(5, net.minecraft.util.math.Direction.SOUTH).getZ());
        assertEquals(-5, ChineseTypologyDetailUtil.octagonFaceCell(5, net.minecraft.util.math.Direction.NORTH).getZ());
    }

    @Test
    void octagonFaceByIndex_cyclesEightFaces() {
        var f0 = ChineseTypologyDetailUtil.octagonFaceByIndex(5, 0);
        var f8 = ChineseTypologyDetailUtil.octagonFaceByIndex(5, 8);
        assertEquals(f0.x(), f8.x());
        assertEquals(f0.z(), f8.z());
        assertNotEquals(
                ChineseTypologyDetailUtil.octagonFaceByIndex(5, 0).x(),
                ChineseTypologyDetailUtil.octagonFaceByIndex(5, 2).x()
        );
    }

    @Test
    void resolvePuzuoProfile_cornerVsEdge() {
        assertEquals(
                ChineseTypologyDetailUtil.PuzuoProfile.INTERIOR_CORNER,
                ChineseTypologyDetailUtil.resolvePuzuoProfile(false, true)
        );
        assertEquals(
                ChineseTypologyDetailUtil.PuzuoProfile.SUB_EAVES_EDGE,
                ChineseTypologyDetailUtil.resolvePuzuoProfile(true, false)
        );
    }

    @Test
    void isCornerColumn_detectsRectangleCorners() {
        assertTrue(ChineseTypologyDetailUtil.isCornerColumn(0, 0, 0, 0, 21, 15));
        assertFalse(ChineseTypologyDetailUtil.isCornerColumn(9, 0, 0, 0, 21, 15));
    }
}
