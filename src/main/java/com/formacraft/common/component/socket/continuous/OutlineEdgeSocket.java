package com.formacraft.common.component.socket.continuous;

import com.formacraft.common.buildcontext.OutlineShape;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * OutlineEdgeSocket：轮廓边界的连续插槽实现。
 */
public final class OutlineEdgeSocket implements ContinuousSocket {
    private final List<BlockPos> points;
    private final boolean closed;

    public OutlineEdgeSocket(List<BlockPos> points, boolean closed) {
        this.points = points != null ? new ArrayList<>(points) : new ArrayList<>();
        this.closed = closed;
    }

    public static OutlineEdgeSocket fromOutlineShape(OutlineShape shape) {
        if (shape == null || shape.vertices() == null || shape.vertices().isEmpty()) {
            return new OutlineEdgeSocket(List.of(), false);
        }

        boolean isClosed = !"free_draw".equalsIgnoreCase(shape.shapeType());
        return new OutlineEdgeSocket(shape.vertices(), isClosed);
    }

    @Override
    public List<BlockPos> samplePoints(int step) {
        if (points.isEmpty()) return List.of();
        return new ArrayList<>(points);
    }

    @Override
    public Vec3d normalAt(int index) {
        if (points.size() < 2 || index < 0 || index >= points.size()) {
            return new Vec3d(0, 1, 0);
        }

        Vec3d tangent = tangentAt(index);
        if (tangent.lengthSquared() < 1e-6) {
            return new Vec3d(0, 1, 0);
        }

        Vec3d up = new Vec3d(0, 1, 0);
        return tangent.crossProduct(up).normalize();
    }

    @Override
    public Vec3d tangentAt(int index) {
        if (points.size() < 2 || index < 0 || index >= points.size()) {
            return new Vec3d(0, 0, 1);
        }

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
