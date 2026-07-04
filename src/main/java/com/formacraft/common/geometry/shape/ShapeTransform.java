package com.formacraft.common.geometry.shape;

/**
 * 欧拉角旋转（度）：world → local 逆变换，用于在 canonical 空间做 inside 测试。
 */
public final class ShapeTransform {

    private ShapeTransform() {}

    public static double[] worldToLocal(double x, double y, double z, ShapeSpec spec) {
        return worldToLocal(x, y, z, spec.rotationXDeg(), spec.rotationYDeg(), spec.rotationZDeg());
    }

    public static double[] worldToLocal(double x, double y, double z, double rotXDeg, double rotYDeg, double rotZDeg) {
        double[] p = rotateZ(x, y, z, -rotZDeg);
        p = rotateY(p[0], p[1], p[2], -rotYDeg);
        p = rotateX(p[0], p[1], p[2], -rotXDeg);
        return p;
    }

    private static double[] rotateX(double x, double y, double z, double deg) {
        if (Math.abs(deg) < 1e-9) {
            return new double[]{x, y, z};
        }
        double rad = Math.toRadians(deg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new double[]{x, y * cos - z * sin, y * sin + z * cos};
    }

    private static double[] rotateY(double x, double y, double z, double deg) {
        if (Math.abs(deg) < 1e-9) {
            return new double[]{x, y, z};
        }
        double rad = Math.toRadians(deg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new double[]{x * cos + z * sin, y, -x * sin + z * cos};
    }

    private static double[] rotateZ(double x, double y, double z, double deg) {
        if (Math.abs(deg) < 1e-9) {
            return new double[]{x, y, z};
        }
        double rad = Math.toRadians(deg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new double[]{x * cos - y * sin, x * sin + y * cos, z};
    }
}
