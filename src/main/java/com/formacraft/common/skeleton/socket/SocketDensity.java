package com.formacraft.common.skeleton.socket;

/**
 * SocketDensity（Socket 密度）：简单但好用的 v1。
 * <p>
 * 用于定义每种 socket 的默认"密度/生成参数"。
 */
public final class SocketDensity {
    /** 生成密度（0~1），用于沿墙/边缘布点 */
    public double density;

    /** opening 的建议尺寸（方块） */
    public int openW;
    public int openH;

    private SocketDensity() {}

    /**
     * 创建开口密度（用于 WALL_OPENING）
     */
    public static SocketDensity openings(double density, int w, int h) {
        SocketDensity d = new SocketDensity();
        d.density = density;
        d.openW = w;
        d.openH = h;
        return d;
    }

    /**
     * 创建稀疏密度（用于装饰性 socket）
     */
    public static SocketDensity sparse(double density) {
        SocketDensity d = new SocketDensity();
        d.density = density;
        d.openW = 0;
        d.openH = 0;
        return d;
    }
}
