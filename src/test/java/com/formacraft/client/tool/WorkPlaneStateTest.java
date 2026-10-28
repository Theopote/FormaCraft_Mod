package com.formacraft.client.tool;

import com.formacraft.common.buildcontext.BuildContext;
import com.formacraft.common.buildcontext.SelectionBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkPlaneStateTest {
    @Test void airDrawingUsesTheRayPlaneIntersectionWithoutAnyTerrainHit() {
        Vec3d hit = WorkPlaneState.intersect(new Vec3d(0, 100, 0), new Vec3d(0.1, -1, 0.1), 80, 100);
        assertNotNull(hit);
        assertEquals(2, hit.x, 1e-8); assertEquals(80, hit.y, 1e-8); assertEquals(2, hit.z, 1e-8);
    }
    @Test void parallelBehindCameraAndBeyondReachCannotCreateDrawingPoints() {
        Vec3d eye = new Vec3d(0, 100, 0);
        assertNull(WorkPlaneState.intersect(eye, new Vec3d(1, 0, 0), 80, 100));
        assertNull(WorkPlaneState.intersect(eye, new Vec3d(0, 1, 0), 80, 100));
        assertNull(WorkPlaneState.intersect(eye, new Vec3d(0, -1, 0), 80, 10));
        assertNull(WorkPlaneState.intersect(eye, Vec3d.ZERO, 80, 100));
        assertNull(WorkPlaneState.intersect(null, new Vec3d(0, -1, 0), 80, 100));
    }
    @Test void negativeWorldCoordinatesAndLookingUpAtAnOverheadPlaneWork() {
        Vec3d hit = WorkPlaneState.intersect(new Vec3d(-0.2, 60, -0.2), new Vec3d(-0.1, 1, -0.1), 70, 100);
        assertNotNull(hit);
        assertEquals(new BlockPos(-2, 70, -2), BlockPos.ofFloored(hit));
    }
    @Test void lockingTerrainModeAndClearingHaveSeparateMeanings() {
        var state = new WorkPlaneState();
        state.toggleLock(); assertFalse(state.fixed());
        state.set(new BlockPos(4, 90, 5)); assertFalse(state.fixed());
        state.toggleLock(); assertTrue(state.fixed());
        state.toggleTerrain(); assertTrue(state.locked()); assertFalse(state.fixed());
        assertEquals(90, state.height());
        state.toggleTerrain(); assertTrue(state.fixed());
        state.clear(); assertFalse(state.hasPlane()); assertFalse(state.locked()); assertFalse(state.followsTerrain());
    }
    @Test void heightEditsClampToDimensionLimitsAndKeepHorizontalPosition() {
        var state = fixed();
        state.setHeight(1000, -64, 319); assertEquals(new BlockPos(4, 319, 5), state.center());
        state.setHeight(-1000, -64, 319); assertEquals(-64, state.height());
        assertTrue(state.fixed());
    }
    @Test void anchorHeightChangesButFacingAndConstraintsArePreserved() {
        var state = fixed();
        var context = BuildContext.forAnchor(new BlockPos(20, 64, 30), Direction.WEST, List.of(), false);
        var resolved = state.apply(context, false);
        assertEquals(new BlockPos(20, 90, 30), resolved.origin);
        assertEquals(Direction.WEST, resolved.facing);
        assertSame(context.protectedZones, resolved.protectedZones);
    }
    @Test void implicitBuildUsesThePlaneCentreAndEditsNeverMove() {
        var state = fixed();
        var implicit = BuildContext.forImplicit(new BlockPos(200, 64, 300), Direction.NORTH, List.of(), false);
        assertEquals(state.center(), state.apply(implicit, false).origin);
        assertSame(implicit, state.apply(implicit, true));
        var selection = new SelectionBox(new BlockPos(0, 60, 0), new BlockPos(10, 80, 10));
        var edit = BuildContext.forSelection(selection, selection.bottomCenter(), Direction.NORTH, List.of(), true);
        assertSame(edit, state.apply(edit, false));
        state.toggleTerrain(); assertSame(implicit, state.apply(implicit, false));
    }
    private static WorkPlaneState fixed() {
        var state = new WorkPlaneState(); state.set(new BlockPos(4, 90, 5)); state.toggleLock(); return state;
    }
}
