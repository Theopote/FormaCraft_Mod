package com.formacraft.common.geometry;

/**
 * Vector2（2D 方向向量，别名）
 * <p>
 * 与 Vec2 相同，但语义上强调"方向"而非"点"。
 * 用于表示法线、方向等。
 */
public final class Vector2 {
    public final double x;
    public final double z;

    public Vector2(double x, double z) {
        this.x = x;
        this.z = z;
    }

    /**
     * 从 Vec2 创建
     */
    public static Vector2 from(Vec2 vec) {
        return new Vector2(vec.x(), vec.z());
    }

    /**
     * 转换为 Vec2
     */
    public Vec2 toVec2() {
        return new Vec2(x, z);
    }

    /**
     * 归一化（单位向量）
     */
    public Vector2 normalize() {
        double len = length();
        if (len == 0) return new Vector2(0, 0);
        return new Vector2(x / len, z / len);
    }

    /**
     * 向量长度
     */
    public double length() {
        return Math.sqrt(x * x + z * z);
    }

    /**
     * 零向量
     */
    public static final Vector2 ZERO = new Vector2(0, 0);

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Vector2 other)) return false;
        return Double.compare(other.x, x) == 0 && Double.compare(other.z, z) == 0;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(x) * 31 + Double.hashCode(z);
    }

    @Override
    public String toString() {
        return String.format("Vector2(%.2f, %.2f)", x, z);
    }
}
