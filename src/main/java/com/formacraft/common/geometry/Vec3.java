package com.formacraft.common.geometry;

/**
 * Vec3（3D 向量/点）
 * <p>
 * 用于表示 3D 空间中的点和向量
 */
public record Vec3(double x, double y, double z) {
    /**
     * 零向量
     */
    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    /**
     * 从 Vec2 和 Y 坐标创建 Vec3
     */
    public static Vec3 from2D(Vec2 vec2, double y) {
        return new Vec3(vec2.x(), y, vec2.z());
    }

    /**
     * 转换为 Vec2（投影到 XZ 平面）
     */
    public Vec2 to2D() {
        return new Vec2(x, z);
    }

    /**
     * 向量加法
     */
    public Vec3 add(Vec3 other) {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    /**
     * 向量减法
     */
    public Vec3 subtract(Vec3 other) {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    /**
     * 标量乘法
     */
    public Vec3 scale(double scalar) {
        return new Vec3(x * scalar, y * scalar, z * scalar);
    }

    /**
     * 向量长度
     */
    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    /**
     * 归一化（单位向量）
     */
    public Vec3 normalize() {
        double len = length();
        if (len == 0) return ZERO;
        return new Vec3(x / len, y / len, z / len);
    }
}
