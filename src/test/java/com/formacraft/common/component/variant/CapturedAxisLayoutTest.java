package com.formacraft.common.component.variant;

import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapturedAxisLayoutTest {
    @Test void horizontalFacingsMapWidthAndDepthToCapturedAxes() {
        for (var facing : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            var axes = CapturedAxisLayout.forFacing(facing);
            boolean sideways = facing == Direction.EAST || facing == Direction.WEST;
            assertEquals(sideways ? 2 : 0, axes.worldAxis(0));
            assertEquals(1, axes.worldAxis(1));
            assertEquals(sideways ? 0 : 2, axes.worldAxis(2));
            assertEquals(sideways ? 7 : 3, axes.dimensionSize(0, 3, 5, 7));
            assertEquals(5, axes.dimensionSize(1, 3, 5, 7));
            assertEquals(sideways ? 3 : 7, axes.dimensionSize(2, 3, 5, 7));
        }
    }

    @Test void editingDisplayedWidthTargetsTheSamePersistedAxisAsPreview() {
        var axes = CapturedAxisLayout.forFacing(Direction.EAST);
        var settings = new CaptureAdaptationSettings();
        settings.modes[axes.worldAxis(0)] = CaptureAdaptationSettings.Mode.REPEAT;
        int[] offsets = {0, 0, 0};
        offsets[axes.worldAxis(0)] = 2;
        assertEquals(CaptureAdaptationSettings.Mode.FIXED, settings.modes[0]);
        assertEquals(CaptureAdaptationSettings.Mode.REPEAT, settings.modes[2]);
        assertArrayEquals(new int[]{0, 0, 2}, offsets);
        assertEquals("正面宽度 Z", axes.label(0));
    }

    @Test void absentFacingRetainsLegacySouthConvention() {
        assertEquals(0, CapturedAxisLayout.forFacing(null).worldAxis(0));
        assertThrows(IllegalArgumentException.class, () -> CapturedAxisLayout.forFacing(null).worldAxis(3));
    }
}
