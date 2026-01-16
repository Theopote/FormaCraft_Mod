package com.formacraft.common.geometry;

/**
 * Vec2（2D 向量/点）
 * <p>
 * 基础几何类型，用于 XZ 平面的点和向量。
 */
public record Vec2(double x, double z) {
    /**
     * 零向量
     */
    public static final Vec2 ZERO = new Vec2(0, 0);

    /**
     * 计算两点之间的距离
     */
    public double distanceTo(Vec2 other) {
        double dx = x - other.x;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 计算两点之间的距离平方（避免开方，用于比较）
     */
    public double distanceSquaredTo(Vec2 other) {
        double dx = x - other.x;
        double dz = z - other.z;
        return dx * dx + dz * dz;
    }

    /**
     * 向量加法
     */
    public Vec2 add(Vec2 other) {
        return new Vec2(x + other.x, z + other.z);
    }

    /**
     * 向量减法
     */
    public Vec2 subtract(Vec2 other) {
        return new Vec2(x - other.x, z - other.z);
    }

    /**
     * 标量乘法
     */
    public Vec2 scale(double scalar) {
        return new Vec2(x * scalar, z * scalar);
    }

    /**
     * 向量长度
     */
    public double length() {
        return Math.sqrt(x * x + z * z);
    }

    /**
     * 归一化（单位向量）
     */
    public Vec2 normalize() {
        double len = length();
        if (len == 0) return ZERO;
        return new Vec2(x / len, z / len);
    }

    /**
     * 点积
     */
    public double dot(Vec2 other) {
        return x * other.x + z * other.z;
    }

    /**
     * 旋转 90 度（用于法线计算）
     */
    public Vec2 rotate90() {
        return new Vec2(-z, x);
    }
}
