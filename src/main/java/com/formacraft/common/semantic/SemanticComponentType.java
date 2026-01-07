package com.formacraft.common.semantic;

/**
 * SemanticComponentType（语义组件类型）
 * <p>
 * K3.1 核心：定义各种语义组件类型，用于组件装配清单
 */
public enum SemanticComponentType {
    // === 主体/体块 ===
    /** 主体体块（店铺/住宅/厂房） */
    MASS_MAIN,
    
    /** 次体块（侧翼/附房） */
    MASS_SECONDARY,

    // === 入口/立面 ===
    /** 门廊/入口 */
    ENTRANCE,
    
    /** 窗/橱窗/立面节奏 */
    FACADE_WINDOWS,
    
    /** 招牌/牌匾/灯箱 */
    SIGNAGE,
    
    /** 阳台/挑檐平台 */
    BALCONY,

    // === 围合/边界 ===
    /** 围墙/矮墙 */
    FENCE_OR_WALL,
    
    /** 门楼/门洞 */
    GATEWAY,

    // === 场地/公共空间 ===
    /** 铺装 */
    PAVING,
    
    /** 广场核心（喷泉/雕塑） */
    PLAZA_CORE,
    
    /** 绿化（树池/花坛） */
    GREENERY,
    
    /** 长椅/小品 */
    BENCHES,

    // === 道路设施 ===
    /** 路灯 */
    STREET_LIGHTS,
    
    /** 摊位/垃圾桶/邮筒等 */
    STREET_FURNITURE,

    // === 工业/结构 ===
    /** 烟囱/排风 */
    CHIMNEY,
    
    /** 堆场/货箱区 */
    YARD_STORAGE,

    // === 防御 ===
    /** 塔楼节点 */
    TOWER_NODE,
    
    /** 垛口 */
    BATTLEMENTS,
    
    /** 巡逻道 */
    WALKWAY_RAMPART
}

