package com.formacraft.common.geometry;

/**
 * Line3D（3D 直线）
 * <p>
 * 用于表示 3D 空间中的直线（脊线等）
 * <p>
 * v3 引入：支持 3D 脊线
 */
public record Line3D(Vec3 start, Vec3 end) {
    /**
     * 计算直线方向向量（归一化）
     */
    public Vec3 direction() {
        Vec3 dir = end.subtract(start);
        return dir.normalize();
    }

    /**
     * 计算直线长度
     */
    public double length() {
        return start.subtract(end).length();
    }

    /**
     * 获取中点
     */
    public Vec3 midpoint() {
        return new Vec3(
                (start.x() + end.x()) / 2.0,
                (start.y() + end.y()) / 2.0,
                (start.z() + end.z()) / 2.0
        );
    }

    /**
     * 投影到 XZ 平面（返回 Line2D）
     */
    public Line2D projectToXZ() {
        return new Line2D(
                new Vec2(start.x(), start.z()),
                new Vec2(end.x(), end.z())
        );
    }
}
