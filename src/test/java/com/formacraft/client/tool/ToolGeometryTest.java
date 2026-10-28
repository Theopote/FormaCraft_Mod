package com.formacraft.client.tool;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ToolGeometryTest {
    @Test void allSixFacesKeepOtherAxesAndInclusiveCorners() {
        BlockPos min = new BlockPos(-5, 10, 20), max = new BlockPos(4, 19, 29);
        for (Direction face : Direction.values()) {
            BlockPos[] same = ToolGeometry.resize(min, max, face, 0);
            assertArrayEquals(new BlockPos[]{min, max}, same, face.toString());
            int delta = face.getDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
            BlockPos[] expanded = ToolGeometry.resize(min, max, face, delta);
            BlockPos moved = face.getDirection() == Direction.AxisDirection.POSITIVE ? max : min;
            BlockPos expected = moved.offset(face);
            assertEquals(face.getDirection() == Direction.AxisDirection.POSITIVE ? min : expected, expanded[0]);
            assertEquals(face.getDirection() == Direction.AxisDirection.POSITIVE ? expected : max, expanded[1]);
        }
    }

    @Test void shrinkingClampsAtOneBlockWithoutFlipping() {
        BlockPos min = new BlockPos(0, 0, 0), max = new BlockPos(9, 9, 9);
        assertEquals(new BlockPos(0, 9, 9), ToolGeometry.resize(min, max, Direction.EAST, -20)[1]);
        assertEquals(new BlockPos(9, 0, 0), ToolGeometry.resize(min, max, Direction.WEST, 20)[0]);
    }

    @Test void cursorRayMovesHandleInAirAndRejectsParallelRay() {
        Vec3d camera = new Vec3d(0, 3, -10);
        Vec3d target = new Vec3d(4, 0, 0);
        Vec3d point = ToolGeometry.axisPoint(camera, target.subtract(camera), Vec3d.ZERO, Direction.Axis.X);
        assertNotNull(point);
        assertEquals(4, point.x, 1e-8);
        assertNull(ToolGeometry.axisPoint(camera, new Vec3d(1, 0, 0), Vec3d.ZERO, Direction.Axis.X));
    }

    @Test void validConcavePolygonWorksInBothOrders() {
        var points = List.of(p(0, 0), p(6, 0), p(6, 6), p(3, 2), p(0, 6));
        assertTrue(ToolGeometry.validPolygon(points));
        assertTrue(ToolGeometry.validPolygon(points.reversed()));
    }

    @Test void invalidFootprintsCannotBeSubmitted() {
        assertFalse(ToolGeometry.validPolygon(List.of(p(0, 0), p(1, 1), p(2, 2))));
        assertFalse(ToolGeometry.validPolygon(List.of(p(0, 0), p(4, 4), p(0, 4), p(4, 0))));
        assertFalse(ToolGeometry.validPolygon(List.of(p(0, 0), p(4, 0), p(4, 0), p(0, 4))));
        assertFalse(ToolGeometry.validPolygon(List.of(p(0, 0), p(4, 0), p(4, 4), p(0, 0), p(0, 4))));
    }

    @Test void closingClickAndConsecutiveHeightSamplesAreNormalized() {
        var points = ToolGeometry.normalizePolygon(List.of(p(0, 0), new BlockPos(0, 70, 0),
                p(4, 0), p(4, 4), p(0, 4), p(0, 0)));
        assertEquals(4, points.size());
        assertTrue(ToolGeometry.validPolygon(points));
    }

    private static BlockPos p(int x, int z) { return new BlockPos(x, 64, z); }
}
