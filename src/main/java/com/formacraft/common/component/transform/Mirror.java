package com.formacraft.common.component.transform;

/**
 * v1：镜像模式（在构件局部坐标系中）。
 *
 * 约定（与 dx/dz 的世界轴一致）：
 * - X：左右镜像（翻转 dx）
 * - Z：前后镜像（翻转 dz）
 */
public enum Mirror {
    NONE,
    X,
    Z
}

