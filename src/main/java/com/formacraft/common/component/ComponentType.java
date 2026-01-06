package com.formacraft.common.component;

/**
 * 建筑的"语义器官"
 * 
 * 不等于方块，不等于 Skeleton
 * 这些是"跨文明的器官"：土楼、城堡、寺庙只是不同组合方式
 */
public enum ComponentType {

    // === 通用结构 ===
    FOUNDATION,
    FLOOR_PLATE,
    ROOF,
    ROOF_RING,
    ROOF_SPIRE,

    // === 墙 / 围合 ===
    ENCLOSING_WALL,
    INNER_PARTITION,
    CURTAIN_WALL,

    // === 塔 / 垂直体 ===
    TOWER,
    CORE,
    KEEP,

    // === 开口 ===
    GATE,
    DOOR,
    WINDOW_BAND,

    // === 场地 / 空间 ===
    COURTYARD,
    PLAZA,
    TERRACE,

    // === 交通 ===
    STAIR,
    RAMP,
    WALKWAY,

    // === 装饰 / 功能 ===
    BALCONY,
    EAVES,
    COLUMN_COLONNADE
}

