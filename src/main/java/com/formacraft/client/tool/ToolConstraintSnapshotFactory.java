package com.formacraft.client.tool;

import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.buildcontext.SelectionBox;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.tool.ToolConstraintSnapshot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 client 工具状态采集 {@link ToolConstraintSnapshot}，供 common 侧空间查询使用。
 *
 * <p>对称工具不在此采集：对称是规划约束，由
 * {@code ChatBuildRequestFactory} 写入 {@code FormaRequest.symmetryMode}。
 */
public final class ToolConstraintSnapshotFactory {
    private ToolConstraintSnapshotFactory() {}

    public static ToolConstraintSnapshot fromTools(
            SelectionTool selection,
            OutlineTool outline,
            ProtectedZoneTool forbidden,
            PathTool path
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

        List<List<Vec3d>> paths = toPaths(path);

        return new ToolConstraintSnapshot(selectionBox, outlineShape, paths, zones);
    }

    public static ToolConstraintSnapshot fromDefaultTools() {
        return fromTools(
                SelectionTool.INSTANCE,
                OutlineTool.INSTANCE,
                ProtectedZoneTool.INSTANCE,
                PathTool.INSTANCE
        );
    }

    private static OutlineShape toOutlineShape(OutlineTool.OutlineShape s) {
        if (s == null) return null;
        if (s.mode() == OutlineMode.CIRCLE) {
            return new OutlineShape("circle", List.of(), s.center(), s.radius(), s.minY(), s.maxY());
        }
        if (s.mode() == OutlineMode.FREE_DRAW) {
            List<BlockPos> points = s.points() != null ? s.points() : List.of();
            return new OutlineShape("free_draw", points, null, 0, s.minY(), s.maxY());
        }
        List<BlockPos> points = s.points() != null ? s.points() : List.of();
        return new OutlineShape("polygon", points, null, 0, s.minY(), s.maxY());
    }

    private static List<List<Vec3d>> toPaths(PathTool pathTool) {
        if (pathTool == null || pathTool.getPathCount() <= 0) {
            return List.of();
        }
        List<List<Vec3d>> result = new ArrayList<>();
        for (PathTool.Path path : pathTool.getGenerationPaths()) {
            if (path != null && path.polyline() != null && !path.polyline().isEmpty()) {
                result.add(path.polyline());
            }
        }
        return result;
    }
}
