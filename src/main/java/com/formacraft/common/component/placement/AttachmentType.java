package com.formacraft.common.component.placement;

/**
 * AttachmentType（附着关系）：构件应附着到哪类“建筑语境”上。
 *
 * 说明：
 * - 这是“高层语义约束”，不是低层 facing。
 * - 具体方向（如果需要）应由 FacingPolicy 从 host/边缘/法线推导。
 */
public enum AttachmentType {
    /** 自由构件（柱子、雕塑） */
    NONE,
    /** 地面/楼板 */
    FLOOR,
    /** 墙面（贴墙装饰、雨棚、壁龛等） */
    WALL_SURFACE,
    /** 墙体洞口（门/窗） */
    WALL_OPENING,
    /** 屋面坡面（老虎窗） */
    ROOF_SURFACE,
    /** 屋檐边缘（飞檐） */
    ROOF_EDGE,
    /** 屋脊（脊兽） */
    ROOF_RIDGE,
    /** 任意边缘（栏杆/护栏） */
    EDGE,
    /** 转角（阳台/塔角装饰等，可能需要 1..2 个外墙面） */
    CORNER
}

