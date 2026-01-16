package com.formacraft.common.component.socket.continuous;

import com.formacraft.client.tool.PathTool;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * PathSocket：来自 PathTool 的连续插槽实现。
 */
public final class PathSocket implements ContinuousSocket {
    private final List<Vec3d> polyline;
    private final boolean closed;

    public PathSocket(List<Vec3d> polyline, boolean closed) {
        this.polyline = polyline != null ? new ArrayList<>(polyline) : new ArrayList<>();
        this.closed = closed;
    }

    /**
     * 从 PathTool.Path 创建 PathSocket
     */
    public static PathSocket fromPathTool(PathTool.Path path) {
        if (path == null || path.polyline() == null || path.polyline().isEmpty()) {
            return new PathSocket(List.of(), false);
        }
        return new PathSocket(path.polyline(), false);
    }

    /**
     * 从 PathTool 的所有路径创建 PathSocket（合并所有路径）
     */
    public static List<PathSocket> fromPathTool(PathTool tool) {
        List<PathSocket> sockets = new ArrayList<>();
        for (PathTool.Path path : tool.getPaths()) {
            PathSocket socket = fromPathTool(path);
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

        // 如果 polyline 已经是离散点，直接转换
        for (Vec3d v : polyline) {
            if (v != null) {
                points.add(BlockPos.ofFloored(v.x, v.y, v.z));
            }
        }

        // v1：如果 step > 1，可以进一步采样（简化处理：直接使用现有点）
        return points;
    }

    @Override
    public Vec3d normalAt(int index) {
        List<BlockPos> points = samplePoints(1);
        if (points.size() < 2 || index < 0 || index >= points.size()) {
            return new Vec3d(0, 1, 0); // 默认向上
        }

        // 计算该点的法线（垂直于路径方向，指向外侧）
        // v1 简化：使用路径的右法线（近似）
        Vec3d tangent = tangentAt(index);
        if (tangent.lengthSquared() < 1e-6) {
            return new Vec3d(0, 1, 0);
        }

        // 右法线 = tangent × UP
        Vec3d up = new Vec3d(0, 1, 0);
        return tangent.crossProduct(up).normalize();
    }

    @Override
    public Vec3d tangentAt(int index) {
        List<BlockPos> points = samplePoints(1);
        if (points.size() < 2 || index < 0 || index >= points.size()) {
            return new Vec3d(0, 0, 1); // 默认向南
        }

        // 计算切线方向
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
            // 最后一个点：使用前一个方向
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
