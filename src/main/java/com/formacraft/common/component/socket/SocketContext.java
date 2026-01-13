package com.formacraft.common.component.socket;

/**
 * SocketContext（Socket 上下文）v1：定义接口的语义位置。
 * <p>
 * 作用：
 * - 快速过滤不合法位置（门不能在屋顶、阳台不能在地面）
 * - 与 PlacementSpec 的 SpatialContext 正交（这个更细粒度）
 */
public enum SocketContext {
    /**
     * 墙面（例如：门、窗、壁画、壁灯）
     */
    WALL,

    /**
     * 边缘（例如：栏杆、飞檐、檐口装饰）
     */
    EDGE,

    /**
     * 角落（例如：角柱、角装饰、转角斗拱）
     */
    CORNER,

    /**
     * 屋顶（例如：烟囱、天窗、屋脊装饰）
     */
    ROOF,

    /**
     * 地面（例如：台阶、地砖、地灯）
     */
    GROUND,

    /**
     * 室内（例如：吊灯、内墙装饰、家具）
     */
    INTERIOR
}
