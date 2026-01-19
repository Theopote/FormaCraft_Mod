package com.formacraft.common.geometry;

/**
 * Line2D（2D 直线）
 * <p>
 * 用于表示轴线、对齐线等。
 */
public record Line2D(Vec2 start, Vec2 end) {
    /**
     * 计算直线方向向量（归一化）
     */
    public Vector2 direction() {
        Vec2 dir = end.subtract(start);
        return Vector2.from(dir.normalize());
    }

    /**
     * 计算直线长度
     */
    public double length() {
        return start.distanceTo(end);
    }

    /**
     * 获取中点
     */
    public Vec2 midpoint() {
        return new Vec2(
                (start.x() + end.x()) / 2.0,
                (start.z() + end.z()) / 2.0
        );
    }

    /**
     * 计算点到直线的距离
     */
    public double distanceToPoint(Vec2 point) {
        Vec2 lineVec = end.subtract(start);
        Vec2 pointVec = point.subtract(start);

        double lineLenSq = lineVec.length() * lineVec.length();
        if (lineLenSq == 0) {
            return start.distanceTo(point);
        }

        double t = pointVec.dot(lineVec) / lineLenSq;
        t = Math.max(0, Math.min(1, t)); // 限制在线段内

        Vec2 closest = start.add(lineVec.scale(t));
        return point.distanceTo(closest);
    }
}
