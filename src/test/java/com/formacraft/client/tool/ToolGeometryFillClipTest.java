package com.formacraft.client.tool;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolGeometryFillClipTest {
    @Test
    void fillStepGrowsWithSpan() {
        assertEquals(1, ToolGeometry.fillStep(0, 10));
        assertEquals(2, ToolGeometry.fillStep(0, 128));
        assertTrue(ToolGeometry.fillStep(0, 640) >= 10);
    }

    @Test
    void polygonFillStripesUsesAdaptiveDensity() {
        List<BlockPos> square = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(200, 64, 0),
                new BlockPos(200, 64, 200),
                new BlockPos(0, 64, 200)
        );
        List<ToolGeometry.FillStripe> stripes = ToolGeometry.polygonFillStripes(square);
        assertFalse(stripes.isEmpty());
        // Adaptive step keeps stripe count near ~64, not hundreds.
        assertTrue(stripes.size() <= 80, "expected adaptive stripe count, got " + stripes.size());
    }

    @Test
    void clipSegmentXZKeepsDiagonalSlopeInsideBox() {
        // Line from (-10,0) to (30,40) clipped into [0,20]x[0,20] should keep proportional ends.
        double[] c = ToolGeometry.clipSegmentXZ(-10, 0, 30, 40, 0, 20, 0, 20);
        assertNotNull(c);
        assertEquals(0.0, c[0], 1e-6);
        assertEquals(10.0, c[1], 1e-6); // enters at x=0 → z=10 along the diagonal
        assertEquals(10.0, c[2], 1e-6); // exits at z=20 → x=10
        assertEquals(20.0, c[3], 1e-6);
    }

    @Test
    void clipSegmentFullyOutsideReturnsNull() {
        assertNull(ToolGeometry.clipSegmentXZ(-5, -5, -1, -1, 0, 10, 0, 10));
    }
}
