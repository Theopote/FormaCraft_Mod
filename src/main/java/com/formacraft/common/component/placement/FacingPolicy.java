package com.formacraft.common.component.placement;

/**
 * FacingPolicy（方向策略）：
 * 将“是否需要方向、方向从哪来”从构件本体中抽离。
 */
public enum FacingPolicy {
    /** 完全不需要方向（柱子、斗拱等） */
    NONE,
    /** 从附着对象推导（门/窗：由墙法线推导外朝向） */
    DERIVED_FROM_HOST,
    /** 朝向外法线（阳台/雨棚：只能在外侧） */
    OUTWARD_NORMAL,
    /** 沿边缘排列（栏杆：沿 edge 走向） */
    ALONG_EDGE,
    /** 明确由工具/玩家指定（少数情况） */
    USER_DEFINED
}

