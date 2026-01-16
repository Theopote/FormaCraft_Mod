package com.formacraft.common.component.socket.continuous;

import com.formacraft.client.tool.OutlineTool;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * OutlineEdgeSocket：来自 OutlineTool 的边界连续插槽实现。
 */
public final class OutlineEdgeSocket implements ContinuousSocket {
    private final List<BlockPos> points;
    private final boolean closed;

    public OutlineEdgeSocket(List<BlockPos> points, boolean closed) {
        this.points = points != null ? new ArrayList<>(points) : new ArrayList<>();
        this.closed = closed;
    }

    /**
     * 从 OutlineTool.OutlineShape 创建 OutlineEdgeSocket
     */
    public static OutlineEdgeSocket fromOutlineTool(OutlineTool.OutlineShape shape) {
        if (shape == null || shape.points == null || shape.points.isEmpty()) {
            return new OutlineEdgeSocket(List.of(), false);
        }

        // Outline 通常是闭合的（polygon/rectangle/circle）
        boolean isClosed = shape.mode != OutlineTool.OutlineMode.FREE_DRAW;

        return new OutlineEdgeSocket(shape.points, isClosed);
    }

    @Override
    public List<BlockPos> samplePoints(int step) {
        if (points.isEmpty()) return List.of();

        // v1：如果 step > 1，可以进一步采样（简化处理：直接使用现有点）
        return new ArrayList<>(points);
    }

    @Override
    public Vec3d normalAt(int index) {
        if (points.size() < 2 || index < 0 || index >= points.size()) {
            return new Vec3d(0, 1, 0); // 默认向上
        }

        // 计算该点的法线（垂直于边界方向，指向外侧）
        Vec3d tangent = tangentAt(index);
        if (tangent.lengthSquared() < 1e-6) {
            return new Vec3d(0, 1, 0);
        }

        // 右法线 = tangent × UP（指向外侧）
        Vec3d up = new Vec3d(0, 1, 0);
        Vec3d right = tangent.crossProduct(up).normalize();
        return right;
    }

    @Override
    public Vec3d tangentAt(int index) {
        if (points.size() < 2 || index < 0 || index >= points.size()) {
            return new Vec3d(0, 0, 1); // 默认向南
        }

        // 计算切线方向
        int nextIndex = (index + 1) % points.size();
        BlockPos cur = points.get(index);
        BlockPos next = points.get(nextIndex);

        Vec3d dir = new Vec3d(
                next.getX() - cur.getX(),
                next.getY() - cur.getY(),
                next.getZ() - cur.getZ()
        );
        double len = dir.length();
        return len > 1e-6 ? dir.multiply(1.0 / len) : new Vec3d(0, 0, 1);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public Object rawGeometry() {
        return points;
    }
}
