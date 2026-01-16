package com.formacraft.common.geometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Polygon2D（2D 多边形）
 * <p>
 * 用于表示 FloorPlate 的 footprint、CourtyardVoid 的轮廓等。
 * <p>
 * 推荐能力：
 * - offset()
 * - area()
 * - centroid()
 * - isClockwise()
 */
public class Polygon2D {
    private final List<Vec2> vertices;

    public Polygon2D(List<Vec2> vertices) {
        this.vertices = vertices != null && !vertices.isEmpty()
                ? new ArrayList<>(vertices)
                : new ArrayList<>();
    }

    /**
     * 从矩形创建
     */
    public static Polygon2D rectangle(Vec2 min, Vec2 max) {
        return new Polygon2D(List.of(
                new Vec2(min.x(), min.z()),
                new Vec2(max.x(), min.z()),
                new Vec2(max.x(), max.z()),
                new Vec2(min.x(), max.z())
        ));
    }

    /**
     * 获取所有顶点
     */
    public List<Vec2> getVertices() {
        return Collections.unmodifiableList(vertices);
    }

    /**
     * 获取顶点数量
     */
    public int vertexCount() {
        return vertices.size();
    }

    /**
     * 计算面积（使用鞋带公式）
     */
    public double area() {
        if (vertices.size() < 3) return 0;

        double sum = 0;
        int n = vertices.size();
        for (int i = 0; i < n; i++) {
            Vec2 current = vertices.get(i);
            Vec2 next = vertices.get((i + 1) % n);
            sum += current.x() * next.z() - next.x() * current.z();
        }
        return Math.abs(sum / 2.0);
    }

    /**
     * 计算质心
     */
    public Vec2 centroid() {
        if (vertices.isEmpty()) return Vec2.ZERO;

        double sumX = 0, sumZ = 0;
        for (Vec2 v : vertices) {
            sumX += v.x();
            sumZ += v.z();
        }
        return new Vec2(sumX / vertices.size(), sumZ / vertices.size());
    }

    /**
     * 判断是否是顺时针（使用面积符号）
     */
    public boolean isClockwise() {
        if (vertices.size() < 3) return false;

        double sum = 0;
        int n = vertices.size();
        for (int i = 0; i < n; i++) {
            Vec2 current = vertices.get(i);
            Vec2 next = vertices.get((i + 1) % n);
            sum += (next.x() - current.x()) * (next.z() + current.z());
        }
        return sum > 0;
    }

    /**
     * 反转顶点顺序
     */
    public Polygon2D reverse() {
        List<Vec2> reversed = new ArrayList<>(vertices);
        Collections.reverse(reversed);
        return new Polygon2D(reversed);
    }

    /**
     * 偏移多边形（向内或向外）
     */
    public Polygon2D offset(double distance) {
        if (vertices.size() < 3) return this;

        List<Vec2> offsetVertices = new ArrayList<>();
        int n = vertices.size();

        for (int i = 0; i < n; i++) {
            Vec2 prev = vertices.get((i - 1 + n) % n);
            Vec2 curr = vertices.get(i);
            Vec2 next = vertices.get((i + 1) % n);

            // 计算两条边的方向
            Vec2 edge1 = curr.subtract(prev);
            Vec2 edge2 = next.subtract(curr);

            // 计算法线
            Vec2 normal1 = new Vec2(-edge1.z(), edge1.x()).normalize();
            Vec2 normal2 = new Vec2(-edge2.z(), edge2.x()).normalize();

            // 计算平分线
            Vec2 bisector = normal1.add(normal2).normalize();
            double angle = Math.acos(normal1.dot(normal2));
            double offsetDist = distance / Math.sin(angle / 2);

            offsetVertices.add(curr.add(bisector.scale(offsetDist)));
        }

        return new Polygon2D(offsetVertices);
    }

    /**
     * 获取边界框
     */
    public Bounds2D getBounds() {
        if (vertices.isEmpty()) return new Bounds2D(Vec2.ZERO, Vec2.ZERO);

        double minX = Double.MAX_VALUE, maxX = Double.MIN_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = Double.MIN_VALUE;

        for (Vec2 v : vertices) {
            minX = Math.min(minX, v.x());
            maxX = Math.max(maxX, v.x());
            minZ = Math.min(minZ, v.z());
            maxZ = Math.max(maxZ, v.z());
        }

        return new Bounds2D(new Vec2(minX, minZ), new Vec2(maxX, maxZ));
    }

    /**
     * 2D 边界框
     */
    public record Bounds2D(Vec2 min, Vec2 max) {}
}
