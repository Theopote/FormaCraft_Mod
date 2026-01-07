package com.formacraft.common.layout;

/**
 * PathSample（路径采样点）
 * 
 * 使用 double 精度，用于路径重采样和朝向计算
 */
public class PathSample {
    public final double x;
    public final double z;

    public PathSample(double x, double z) {
        this.x = x;
        this.z = z;
    }
}

