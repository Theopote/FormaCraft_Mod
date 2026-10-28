package com.formacraft.client.tool;

import com.formacraft.common.skeleton.PathSkeleton;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PathToolTest {
    @Test void disconnectedPathsNeverBecomeOneSkeleton() {
        var tool = new PathTool();
        path(tool, 0); path(tool, 100);
        assertEquals(2, tool.getPathCount());
        assertEquals(List.of(new BlockPos(100, 64, 0), new BlockPos(110, 64, 0)), tool.toSkeleton().nodes);
        tool.selectNextPath();
        assertEquals(List.of(new BlockPos(0, 64, 0), new BlockPos(10, 64, 0)), tool.toSkeleton().nodes);
        assertEquals(1, tool.getGenerationPaths().size());
    }

    @Test void drawingModeChangesNewPathsAndKeepsExistingGeometry() {
        var tool = new PathTool();
        path(tool, 0);
        var first = tool.getPaths().getFirst();
        assertEquals(2, first.polyline().size());
        tool.cycleDrawingMode();
        path(tool, 100);
        assertTrue(tool.getPaths().getLast().polyline().size() > 2);
        assertSame(first, tool.getPaths().getFirst());
        var nodes = tool.toSkeleton().nodes;
        for (int i = 1; i < nodes.size(); i++) assertNotEquals(nodes.get(i - 1), nodes.get(i));
    }

    @Test void radiusAndExplicitIntentReachTheExportedSkeleton() {
        var tool = new PathTool();
        path(tool, 0);
        tool.changeCorridorRadius(100);
        tool.setIntent(PathSkeleton.PathIntent.WALL);
        assertEquals(32, tool.toSkeleton().corridorRadius);
        assertEquals(PathSkeleton.PathIntent.WALL, tool.toSkeleton().intent);
        tool.changeCorridorRadius(-100);
        assertEquals(1, tool.toSkeleton().corridorRadius);
    }

    @Test void deletingSelectionAndClearingCannotLeaveStaleNodes() {
        var tool = new PathTool();
        path(tool, 0); path(tool, 100);
        tool.removeSelectedPath();
        assertEquals(0, tool.getSelectedPathIndex());
        assertEquals(0, tool.toSkeleton().nodes.getFirst().getX());
        tool.clearAll();
        assertEquals(-1, tool.getSelectedPathIndex());
        assertFalse(tool.toSkeleton().isValid());
        assertTrue(tool.getGenerationPaths().isEmpty());
    }

    @Test void duplicateClicksDoNotCreateAZeroLengthPath() {
        var tool = new PathTool();
        tool.addDraftPoint(new Vec3d(0.5, 64.05, 0.5));
        tool.addDraftPoint(new Vec3d(0.5, 64.05, 0.5));
        assertFalse(tool.finishDraft());
        tool.cancelDraft();
        assertEquals(0, tool.getPathCount());
    }

    @Test void constraintSnapshotUsesTheSameSelectedPathAsTheRequest() {
        var tool = new PathTool();
        path(tool, 0); path(tool, 100);
        var snapshot = ToolConstraintSnapshotFactory.fromTools(null, null, null, null, tool);
        assertEquals(List.of(tool.getPaths().getLast().polyline()), snapshot.paths);
        tool.selectNextPath();
        assertEquals(List.of(tool.getPaths().getFirst().polyline()),
                ToolConstraintSnapshotFactory.fromTools(null, null, null, null, tool).paths);
        assertEquals(100, snapshot.paths.getFirst().getFirst().x, 0.6);
    }

    private static void path(PathTool tool, int x) {
        tool.addDraftPoint(new Vec3d(x + 0.5, 64.05, 0.5));
        tool.addDraftPoint(new Vec3d(x + 10.5, 64.05, 0.5));
        assertTrue(tool.finishDraft());
    }
}
