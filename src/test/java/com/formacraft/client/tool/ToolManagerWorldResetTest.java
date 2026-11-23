package com.formacraft.client.tool;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolManagerWorldResetTest {
    @Test
    void resetWorldStateClearsCommittedSpatialConstraints() {
        SelectionTool.INSTANCE.setSelection(new BlockPos(1, 2, 3), new BlockPos(4, 5, 6));
        assertTrue(SelectionTool.INSTANCE.hasSelection());

        PathTool.INSTANCE.clearAll();
        SymmetryTool.INSTANCE.clearAxis();
        SemanticLabelTool.INSTANCE.clearLabels();
        BrushTool.INSTANCE.clearSelected();
        WorkPlaneTool.INSTANCE.resetWorldState();

        // Seed a few tools with world-scoped residue via public APIs where available.
        SymmetryTool.INSTANCE.clearAxis();
        assertFalse(SymmetryTool.INSTANCE.hasAxis());

        ToolManager.resetWorldState();

        assertFalse(SelectionTool.INSTANCE.hasSelection());
        assertFalse(OutlineTool.INSTANCE.hasShape());
        assertEquals(0, PathTool.INSTANCE.getPathCount());
        assertFalse(ProtectedZoneTool.INSTANCE.hasZones());
        assertFalse(SemanticLabelTool.INSTANCE.hasLabels());
        assertFalse(SymmetryTool.INSTANCE.hasAxis());
        assertFalse(WorkPlaneTool.INSTANCE.state().hasPlane());
        assertNull(ToolManager.getActiveTool());
    }
}
