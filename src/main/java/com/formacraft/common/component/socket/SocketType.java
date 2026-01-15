package com.formacraft.common.component.socket;

/**
 * SocketType（插槽类型）：建筑表面的"可接受接口"。
 * <p>
 * 核心思想：
 * - Socket = 建筑表面的"可接受接口"
 * - 墙不是"一个面"，墙是一组可被插入的 socket
 * - 构件不是"随便贴"，构件是声明：我需要哪种 socket
 * <p>
 * ⚠️ 注意：
 * - 没有 NORTH / SOUTH / EAST / WEST
 * - 方向不是 socket 的职责
 */
public enum SocketType {
    /** 墙面（可贴） */
    WALL_SURFACE,

    /** 墙洞（门/窗） */
    WALL_OPENING,

    /** 外轮廓边缘（栏杆/阳台） */
    EDGE_OUTER,

    /** 屋面 */
    ROOF_SLOPE,

    /** 屋脊 */
    ROOF_RIDGE,

    /** 地面 */
    FLOOR_SURFACE,

    /** 柱顶 */
    COLUMN_TOP,

    /** 自由（装饰） */
    FREE_ATTACH
}
