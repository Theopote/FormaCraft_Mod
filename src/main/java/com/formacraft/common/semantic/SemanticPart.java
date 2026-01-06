package com.formacraft.common.semantic;

/**
 * 建筑"语义部位"——跨风格通用
 */
public enum SemanticPart {
    // 通用结构
    FOUNDATION,
    WALL,
    PILLAR,
    BEAM,
    FLOOR,
    ROOF,
    TRIM,

    // 道路 / 桥
    PATH_BASE,
    PATH_EDGE,
    STAIRS,
    RAILING,

    // 开口
    DOORWAY,
    WINDOW,

    // 防御类
    BATTLEMENT,
    TOWER_CORE,
    TOWER_TRIM,

    // 装饰/光源
    LIGHT,
    DECOR
}

