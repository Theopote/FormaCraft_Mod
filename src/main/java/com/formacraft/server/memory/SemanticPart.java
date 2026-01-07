package com.formacraft.server.memory;

/**
 * 建筑语义部位枚举
 * 用于标识 Patch 修改影响的建筑部位
 */
public enum SemanticPart {
    /** 地基/基础 */
    FOUNDATION,
    
    /** 墙体 */
    WALL,
    
    /** 屋顶 */
    ROOF,
    
    /** 窗户 */
    WINDOW,
    
    /** 门 */
    DOOR,
    
    /** 路径/道路 */
    PATH,
    
    /** 装饰 */
    DECORATION,
    
    /** 未知/未分类 */
    UNKNOWN
}

