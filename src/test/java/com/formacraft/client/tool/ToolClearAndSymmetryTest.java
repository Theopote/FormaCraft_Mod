package com.formacraft.client.tool;

import com.formacraft.common.buildcontext.SelectionBox;
import com.formacraft.common.geometry.tool.symmetry.SymmetryPlane;
import com.formacraft.common.geometry.tool.symmetry.SymmetryProcessor;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchFilterContext;
import com.formacraft.common.patch.filter.impl.SymmetryFilter;
import com.formacraft.common.tool.MirrorSymmetryMode;
import com.formacraft.common.tool.ToolConstraintSnapshot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ToolClearAndSymmetryTest {
    @Test void clearingZonesRemovesCommittedAndDraftStateAndSyncsEmptySnapshot() {
        List<List<ProtectedZone>> updates = new ArrayList<>();
        var tool = new ProtectedZoneTool(updates::add);
        assertFalse(tool.canClear());
        tool.selectPoint(new BlockPos(0, 64, 0));
        assertTrue(tool.canClear());
        tool.selectPoint(new BlockPos(4, 70, 4));
        assertEquals(1, tool.getZones().size());
        tool.selectPoint(new BlockPos(20, 64, 20));
        tool.clearZones();
        assertFalse(tool.canClear());
        assertFalse(tool.hasZones());
        assertFalse(tool.hasPersistentOverlay());
        assertEquals(List.of(), updates.getLast());
        assertEquals(1, updates.getFirst().size(), "in-flight snapshots must not alias the cleared list");
        tool.selectPoint(new BlockPos(30, 64, 30));
        assertFalse(tool.hasZones(), "clearing must also reset the unfinished first corner");
        tool.selectPoint(new BlockPos(32, 68, 32));
        assertEquals(new BlockPos(30, 64, 30), tool.getZones().getFirst().min());
    }

    @Test void clearAxisAvailabilityTracksDraftAndCommittedCustomAxesOnly() {
        var tool = new SymmetryTool();
        assertFalse(tool.canClearAxis());
        tool.cycleMode();
        assertFalse(tool.canClearAxis(), "preset mirror planes are not custom axes");
        while (tool.getMode() != SymmetryMode.CUSTOM_AXIS) tool.cycleMode();
        tool.selectAxisPoint(new BlockPos(1, 64, 1));
        assertTrue(tool.canClearAxis());
        tool.selectAxisPoint(new BlockPos(1, 64, 8));
        assertTrue(tool.hasAxis());
        tool.clearAxis();
        assertFalse(tool.canClearAxis());
        assertFalse(tool.hasAxis());
    }

    @Test void xyzPlanesHaveDistinctColoursAndCorrectOrientationsWithoutSelection() {
        var origin = new BlockPos(10, 64, -20);
        var x = SymmetryOverlayGeometry.planes(SymmetryMode.MIRROR_X, null, origin, null, null).getFirst();
        var y = SymmetryOverlayGeometry.planes(SymmetryMode.MIRROR_Y, null, origin, null, null).getFirst();
        var z = SymmetryOverlayGeometry.planes(SymmetryMode.MIRROR_Z, null, origin, null, null).getFirst();
        assertEquals(10.5, x.corner().x);
        assertEquals(64.5, y.corner().y);
        assertEquals(-19.5, z.corner().z);
        assertEquals(0, x.u().x); assertEquals(0, x.v().x);
        assertEquals(0, y.u().y); assertEquals(0, y.v().y);
        assertEquals(0, z.u().z); assertEquals(0, z.v().z);
        assertTrue(x.red() > x.green());
        assertTrue(y.green() > y.red());
        assertTrue(z.blue() > z.red());
        assertTrue(x.v().y >= 12);
        assertEquals(2, SymmetryOverlayGeometry.planes(SymmetryMode.BOTH, null, origin, null, null).size());
        assertTrue(SymmetryOverlayGeometry.planes(SymmetryMode.NONE, null, origin, null, null).isEmpty());
    }

    @Test void verticalMirrorAndItsOverlayAgreeOnSelectionPlane() {
        var selection = new SelectionBox(new BlockPos(-4, 60, -4), new BlockPos(4, 68, 4));
        var snapshot = new ToolConstraintSnapshot(selection, null, List.of(), List.of(),
                new ToolConstraintSnapshot.SymmetryConstraint(MirrorSymmetryMode.MIRROR_Y, null, null));
        BlockPos origin = new BlockPos(0, 60, 0);
        var input = new BlockPatch(BlockPatch.PLACE, 1, 2, 0, "minecraft:stone");
        var output = new SymmetryFilter().filter(List.of(input), origin, new PatchFilterContext(snapshot));
        assertEquals(2, output.size());
        int mirroredY = origin.getY() + output.getLast().dy();
        assertEquals(66, mirroredY);
        double planeY = SymmetryOverlayGeometry.planes(SymmetryMode.MIRROR_Y, selection, origin, null, null).getFirst().corner().y;
        assertEquals(planeY, ((62 + 0.5) + (mirroredY + 0.5)) / 2);
        var processor = new SymmetryProcessor(new SymmetryPlane(SymmetryPlane.Axis.Y, 64));
        assertEquals(new BlockPos(1, 66, 0), processor.mirror(new BlockPos(1, 62, 0)));
        assertEquals(Direction.DOWN, processor.mirrorFacing(Direction.UP));
        assertEquals(Direction.NORTH, processor.mirrorFacing(Direction.NORTH));
    }
}
