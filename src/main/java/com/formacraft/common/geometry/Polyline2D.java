package com.formacraft.common.geometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Polyline2D（2D 折线）
 * <p>
 * 用于表示墙基线、路径等。
 * <p>
 * 支持：
 * - 折线墙
 * - 锯齿平面
 * - v1 仍然可以只用 2 点（直线）
 */
public class Polyline2D {
    private final List<Vec2> points;

    public Polyline2D(List<Vec2> points) {
        this.points = points != null && !points.isEmpty()
                ? new ArrayList<>(points)
                : new ArrayList<>();
    }

    /**
     * 从两个点创建直线
     */
    public static Polyline2D line(Vec2 start, Vec2 end) {
        return new Polyline2D(List.of(start, end));
    }

    /**
     * 获取所有点
     */
    public List<Vec2> getPoints() {
        return Collections.unmodifiableList(points);
    }

    /**
     * 获取点的数量
     */
    public int pointCount() {
        return points.size();
    }

    /**
     * 获取起始点
     */
    public Vec2 getStart() {
        return points.isEmpty() ? null : points.get(0);
    }

    /**
     * 获取结束点
     */
    public Vec2 getEnd() {
        return points.isEmpty() ? null : points.get(points.size() - 1);
    }

    /**
     * 计算总长度
     */
    public double totalLength() {
        if (points.size() < 2) return 0;
        double total = 0;
        for (int i = 0; i < points.size() - 1; i++) {
            total += points.get(i).distanceTo(points.get(i + 1));
        }
        return total;
    }

    /**
     * 偏移折线（用于墙的厚度）
     */
    public Polyline2D offset(double distance) {
        if (points.size() < 2) return this;

        List<Vec2> offsetPoints = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            Vec2 point = points.get(i);
            Vec2 offset;

            if (i == 0) {
                // 第一个点：沿第一个线段的方向偏移
                Vec2 dir = points.get(1).subtract(point);
                Vec2 normal = new Vec2(-dir.z(), dir.x()).normalize();
                offset = normal.scale(distance);
            } else if (i == points.size() - 1) {
                // 最后一个点：沿最后一个线段的方向偏移
                Vec2 dir = point.subtract(points.get(i - 1));
                Vec2 normal = new Vec2(-dir.z(), dir.x()).normalize();
                offset = normal.scale(distance);
            } else {
                // 中间点：沿两个线段夹角的平分线偏移
                Vec2 prevDir = point.subtract(points.get(i - 1));
                Vec2 nextDir = points.get(i + 1).subtract(point);
                Vec2 prevNormal = new Vec2(-prevDir.z(), prevDir.x()).normalize();
                Vec2 nextNormal = new Vec2(-nextDir.z(), nextDir.x()).normalize();
                Vec2 bisector = prevNormal.add(nextNormal).normalize();
                double angle = Math.acos(prevNormal.dot(nextNormal));
                double offsetDist = distance / Math.sin(angle / 2);
                offset = bisector.scale(offsetDist);
            }

            offsetPoints.add(point.add(offset));
        }

        return new Polyline2D(offsetPoints);
    }

    /**
     * 是否是直线（只有两个点）
     */
    public boolean isLine() {
        return points.size() == 2;
    }
}
