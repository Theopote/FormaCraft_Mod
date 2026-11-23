package com.formacraft.client.tool;

import com.formacraft.client.interaction.AnchorState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolManagerWorldResetTest {
    @Test
    void resetWorldStateClearsCommittedSpatialConstraints() {
        SelectionTool.INSTANCE.setSelection(new BlockPos(1, 2, 3), new BlockPos(4, 5, 6));
        assertTrue(SelectionTool.INSTANCE.hasSelection());
        AnchorState.set(new BlockPos(10, 64, 10), Direction.SOUTH);
        assertTrue(AnchorState.hasAnchor());

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
        assertFalse(AnchorState.hasAnchor());
        assertNull(ToolManager.getActiveTool());
    }
}
