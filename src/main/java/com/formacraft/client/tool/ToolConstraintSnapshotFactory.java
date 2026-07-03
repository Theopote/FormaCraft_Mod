package com.formacraft.client.tool;

import com.formacraft.client.buildcontext.BuildContextResolver;
import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.buildcontext.SelectionBox;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.tool.MirrorSymmetryMode;
import com.formacraft.common.tool.ToolConstraintSnapshot;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 从 client 工具状态采集 {@link ToolConstraintSnapshot}，供 common/server 过滤管线使用。
 */
public final class ToolConstraintSnapshotFactory {
    private ToolConstraintSnapshotFactory() {}

    public static ToolConstraintSnapshot fromTools(
            SelectionTool selection,
            OutlineTool outline,
            SymmetryTool symmetry,
            ProtectedZoneTool forbidden
    ) {
        SelectionBox selectionBox = null;
        if (selection != null && selection.hasSelection()) {
            selectionBox = new SelectionBox(selection.getMin(), selection.getMax()).normalized();
        }

        OutlineShape outlineShape = null;
        if (outline != null && outline.hasShape()) {
            outlineShape = toOutlineShape(outline.getShape());
        }

        List<ProtectedZone> zones = (forbidden != null && forbidden.hasZones())
                ? forbidden.getZones()
                : List.of();

        ToolConstraintSnapshot.SymmetryConstraint symmetryConstraint = toSymmetry(symmetry);

        return new ToolConstraintSnapshot(selectionBox, outlineShape, zones, symmetryConstraint);
    }

    public static ToolConstraintSnapshot fromDefaultTools() {
        return fromTools(
                SelectionTool.INSTANCE,
                OutlineTool.INSTANCE,
                SymmetryTool.INSTANCE,
                ProtectedZoneTool.INSTANCE
        );
    }

    private static OutlineShape toOutlineShape(OutlineTool.OutlineShape s) {
        if (s == null) return null;
        if (s.mode() == OutlineMode.CIRCLE) {
            return new OutlineShape("circle", List.of(), s.center(), s.radius(), s.minY(), s.maxY());
        }
        List<BlockPos> points = s.points() != null ? s.points() : List.of();
        return new OutlineShape("polygon", points, null, 0, s.minY(), s.maxY());
    }

    private static ToolConstraintSnapshot.SymmetryConstraint toSymmetry(SymmetryTool tool) {
        if (tool == null) {
            return ToolConstraintSnapshot.SymmetryConstraint.none();
        }
        SymmetryMode mode = tool.getMode();
        if (mode == null || mode == SymmetryMode.NONE) {
            return ToolConstraintSnapshot.SymmetryConstraint.none();
        }
        MirrorSymmetryMode commonMode = MirrorSymmetryMode.valueOf(mode.name());
        BlockPos axisA = tool.hasAxis() ? tool.getAxisA() : null;
        BlockPos axisB = tool.hasAxis() ? tool.getAxisB() : null;
        return new ToolConstraintSnapshot.SymmetryConstraint(commonMode, axisA, axisB);
    }
}
