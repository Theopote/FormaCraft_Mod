package com.formacraft.common.component.socket.continuous;

import com.formacraft.common.tool.ToolConstraintSnapshot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * PathSocket：路径折线的连续插槽实现。
 */
public final class PathSocket implements ContinuousSocket {
    private final List<Vec3d> polyline;
    private final boolean closed;

    public PathSocket(List<Vec3d> polyline, boolean closed) {
        this.polyline = polyline != null ? new ArrayList<>(polyline) : new ArrayList<>();
        this.closed = closed;
    }

    public static PathSocket fromPolyline(List<Vec3d> polyline) {
        if (polyline == null || polyline.isEmpty()) {
            return new PathSocket(List.of(), false);
        }
        return new PathSocket(polyline, false);
    }

    public static List<PathSocket> fromSnapshot(ToolConstraintSnapshot snapshot) {
        List<PathSocket> sockets = new ArrayList<>();
        if (snapshot == null || !snapshot.hasPaths()) {
            return sockets;
        }
        for (List<Vec3d> path : snapshot.paths) {
            PathSocket socket = fromPolyline(path);
            if (socket.polyline.size() >= 2) {
                sockets.add(socket);
            }
        }
        return sockets;
    }

    @Override
    public List<BlockPos> samplePoints(int step) {
        if (polyline.isEmpty()) return List.of();

        List<BlockPos> points = new ArrayList<>();
        if (step <= 0) {
        }

        for (Vec3d v : polyline) {
            if (v != null) {
                points.add(BlockPos.ofFloored(v.x, v.y, v.z));
            }
        }

        return points;
    }

    @Override
    public Vec3d normalAt(int index) {
        List<BlockPos> points = samplePoints(1);
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
        List<BlockPos> points = samplePoints(1);
        if (points.size() < 2 || index < 0 || index >= points.size()) {
            return new Vec3d(0, 0, 1);
        }

        if (index < points.size() - 1) {
            BlockPos cur = points.get(index);
            BlockPos next = points.get(index + 1);
            Vec3d dir = new Vec3d(
                    next.getX() - cur.getX(),
                    next.getY() - cur.getY(),
                    next.getZ() - cur.getZ()
            );
            double len = dir.length();
            return len > 1e-6 ? dir.multiply(1.0 / len) : new Vec3d(0, 0, 1);
        } else {
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

    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public Object rawGeometry() {
        return polyline;
    }
}
