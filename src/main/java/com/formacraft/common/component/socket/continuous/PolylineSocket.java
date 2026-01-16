package com.formacraft.common.component.socket.continuous;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * PolylineSocket：任意折线的连续插槽实现。
 */
public final class PolylineSocket implements ContinuousSocket {
    private final List<BlockPos> points;
    private final boolean closed;

    public PolylineSocket(List<BlockPos> points, boolean closed) {
        this.points = points != null ? new ArrayList<>(points) : new ArrayList<>();
        this.closed = closed;
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
        Vec3d right = tangent.crossProduct(up).normalize();
        return right;
    }

    @Override
    public Vec3d tangentAt(int index) {
        if (points.size() < 2 || index < 0 || index >= points.size()) {
            return new Vec3d(0, 0, 1);
        }

        int nextIndex = closed ? ((index + 1) % points.size()) : (index + 1);
        if (nextIndex >= points.size()) {
            // 最后一个点：使用前一个方向
            if (index > 0) {
                BlockPos prev = points.get(index - 1);
                BlockPos cur = points.get(index);
                Vec3d dir = new Vec3d(
                        cur.getX() - prev.getX(),
                        cur.getY() - prev.getY(),
                        cur.getZ() - prev.getZ()
                );
                double len = dir.length();
                return len > 1e-6 ? dir.multiply(1.0 / len) : new Vec3d(0, 0, 1);
            }
            return new Vec3d(0, 0, 1);
        }

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
