package com.formacraft.common.component.socket;

/**
 * SocketFacingPolicy（Socket 朝向策略）v1：定义接口的朝向约束。
 * <p>
 * 作用：
 * - 决定组件需要哪种朝向信息
 * - 简化 AI/Tool 的决策复杂度
 */
public enum SocketFacingPolicy {
    /**
     * 无朝向（例如：柱子、吊灯、地砖）
     * - 不关心朝向
     * - 只需要位置
     */
    NONE,

    /**
     * 内外朝向（例如：门、窗、阳台）
     * - 只关心"哪边是内、哪边是外"
     * - 不关心东南西北
     * - 对应 PlacementSpec 的 has_interior_exterior
     */
    IN_OUT,

    /**
     * 轴向对齐（例如：栏杆、墙段、路径）
     * - 只关心 X 轴或 Z 轴
     * - 不关心正负方向
     */
    AXIS,

    /**
     * 自由朝向（例如：旗帜、路标、装饰）
     * - 可以任意朝向
     * - 由 AI/玩家自由决定
     */
    FREE
}
