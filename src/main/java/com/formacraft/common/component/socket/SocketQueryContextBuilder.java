package com.formacraft.common.component.socket;

import com.formacraft.client.tool.OutlineTool;
import com.formacraft.client.tool.PathTool;
import com.formacraft.client.tool.SelectionTool;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;

/**
 * SocketQueryContextBuilder（Socket 查询上下文构建器）。
 * <p>
 * 把 Tool 状态接进 SocketQueryContext（关键）。
 * <p>
 * 示例用法：
 * <pre>
 * SocketQueryContext ctx = SocketQueryContextBuilder.fromTools(
 *     SelectionTool.INSTANCE,
 *     OutlineTool.INSTANCE,
 *     PathTool.INSTANCE,
 *     hitPosVec
 * );
 * List&lt;Socket&gt; sockets = SocketProviders.collect(world, ctx);
 * </pre>
 */
public final class SocketQueryContextBuilder {
    private SocketQueryContextBuilder() {}

    /**
     * 从工具实例创建 SocketQueryContext
     * 
     * @param selectionTool 选区工具（可选）
     * @param outlineTool 轮廓工具（可选）
     * @param pathTool 路径工具（可选）
     * @param focus 焦点位置（鼠标 hit 或 anchor）
     * @return SocketQueryContext
     */
    public static SocketQueryContext fromTools(
            SelectionTool selectionTool,
            OutlineTool outlineTool,
            PathTool pathTool,
            Vec3d focus
    ) {
        SocketQueryContext ctx = new SocketQueryContext();
        ctx.focus = focus != null ? focus : Vec3d.ZERO;
        ctx.radius = 48;

        // SelectionTool
        if (selectionTool != null && selectionTool.hasSelection()) {
            ctx.selectionMin = selectionTool.getMin();
            ctx.selectionMax = selectionTool.getMax();
        }

        // OutlineTool
        if (outlineTool != null && outlineTool.hasShape()) {
            OutlineTool.OutlineShape shape = outlineTool.getShape();
            if (shape != null && shape.points != null) {
                for (BlockPos p : shape.points) {
                    // 使用 shape 的 y 坐标，或 focus 的 y
                    double y = focus != null ? focus.y : (shape.minY != 0 ? shape.minY : p.getY());
                    ctx.outlinePolygon.add(new Vec3d(p.getX(), y, p.getZ()));
                }
            }
        }

        // PathTool
        if (pathTool != null && pathTool.getPathCount() > 0) {
            // 使用 PathTool 的 getNodes() 方法获取路径点
            List<net.minecraft.util.math.BlockPos> nodes = pathTool.getNodes();
            if (nodes != null && !nodes.isEmpty()) {
                List<Vec3d> pathPoints = new ArrayList<>();
                for (BlockPos node : nodes) {
                    pathPoints.add(new Vec3d(node.getX() + 0.5, node.getY(), node.getZ() + 0.5));
                }
                if (!pathPoints.isEmpty()) {
                    ctx.paths.add(pathPoints);
                }
            }
        }

        // openings
        ctx.includeOpenings = true;

        return ctx;
    }
}
