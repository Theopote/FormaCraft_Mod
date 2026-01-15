package com.formacraft.common.component.archetype;

/**
 * ContextType（上下文类型）：构件可以放置的建筑上下文。
 */
public enum ContextType {
    /**
     * WALL - 墙体上下文
     */
    WALL,

    /**
     * ROOF - 屋顶上下文
     */
    ROOF,

    /**
     * FLOOR - 地板上下文
     */
    FLOOR,

    /**
     * EDGE - 边缘上下文
     */
    EDGE,

    /**
     * CORNER - 角落上下文
     */
    CORNER,

    /**
     * FREE - 自由放置（无特定上下文要求）
     */
    FREE
}
