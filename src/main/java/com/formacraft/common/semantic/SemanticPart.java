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
    WALKWAY_FLOOR,

    // 开口
    DOORWAY,
    WINDOW,
    GATE_OPENING,
    GATE_LINTEL,

    // 楼梯
    STAIR_STEP,

    // 防御类
    BATTLEMENT,
    TOWER_CORE,
    TOWER_TRIM,
    TOWER_WALL,

    // 场地 / 空间
    COURTYARD_FLOOR,

    // 屋顶
    ROOF_SURFACE,

    // 装饰/光源
    LIGHT,
    DECOR,

    // 扩展：用于 ComponentGenerator 的专用部位
    WALL_BASE,      // 墙体基础（地基）
    WALL_ACCENT,    // 墙体装饰（强调）
    ROAD_SURFACE,   // 道路表面
    ROAD_EDGE       // 道路边缘
}

