package com.formacraft.common.component.socket;

/**
 * SocketShape（Socket 形状）v1：定义接口的几何形状。
 * <p>
 * 作用：
 * - 快速过滤不兼容的形状（RECT 不能插 LINE）
 * - 决定尺寸约束的维度（POINT 无尺寸，LINE 一维，RECT 二维）
 */
public enum SocketShape {
    /**
     * 点（例如：灯、装饰物、旗帜）
     * - 无尺寸约束
     * - 只有位置 + 朝向
     */
    POINT,

    /**
     * 线（例如：栏杆、飞檐、边缘装饰）
     * - 一维尺寸约束（长度）
     * - 需要轴向对齐
     */
    LINE,

    /**
     * 矩形（例如：门、窗、阳台、壁画）
     * - 二维尺寸约束（宽 × 高）
     * - 需要平面对齐
     */
    RECT,

    /**
     * 环形（例如：圆窗、斗拱圈、拱门）
     * - 一维尺寸约束（半径）
     * - 需要中心对齐
     */
    RING
}
