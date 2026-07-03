package com.formacraft.common.component.socket;

import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.tool.ToolConstraintSnapshot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * SocketQueryContextBuilder（Socket 查询上下文构建器）。
 * <p>
 * 从 {@link ToolConstraintSnapshot} 构建 {@link SocketQueryContext}，common 侧不依赖 client 工具类。
 */
public final class SocketQueryContextBuilder {
    private SocketQueryContextBuilder() {}

    /**
     * 从工具约束快照创建 SocketQueryContext。
     *
     * @param snapshot 工具状态快照（由 client 侧 {@code ToolConstraintSnapshotFactory} 采集）
     * @param focus    焦点位置（鼠标 hit 或 anchor）
     */
    public static SocketQueryContext fromSnapshot(ToolConstraintSnapshot snapshot, Vec3d focus) {
        SocketQueryContext ctx = new SocketQueryContext();
        ctx.focus = focus != null ? focus : Vec3d.ZERO;
        ctx.radius = 48;

        if (snapshot != null && snapshot.hasSelection()) {
            ctx.selectionMin = snapshot.selection.min();
            ctx.selectionMax = snapshot.selection.max();
        }

        if (snapshot != null && snapshot.hasOutline()) {
            OutlineShape shape = snapshot.outline;
            if (shape.vertices() != null) {
                for (BlockPos p : shape.vertices()) {
                    double y = focus != null ? focus.y : p.getY();
                    ctx.outlinePolygon.add(new Vec3d(p.getX(), y, p.getZ()));
                }
            }
        }

        if (snapshot != null && snapshot.hasPaths()) {
            ctx.paths.addAll(snapshot.paths);
        }

        ctx.includeOpenings = true;
        return ctx;
    }
}
