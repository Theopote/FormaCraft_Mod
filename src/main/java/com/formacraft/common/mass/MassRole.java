package com.formacraft.common.mass;

/**
 * MassRole（体量角色）
 * <p>
 * AI 和规则真正关心的语义角色
 * <p>
 * ⚠️ 注意：
 * - 这不是几何属性
 * - 这是给 AI / 后续规则用的语义标签
 */
public enum MassRole {
    /**
     * 主体（PRIMARY）
     * <p>
     * 用途：建筑的主要体量
     */
    PRIMARY,

    /**
     * 附属（SECONDARY）
     * <p>
     * 用途：侧翼、配殿、辅助体量
     */
    SECONDARY,

    /**
     * 悬挑（CANTILEVER）
     * <p>
     * 用途：悬挑阳台、挑出的檐廊
     */
    CANTILEVER,

    /**
     * 核心筒（CORE）
     * <p>
     * 用途：核心结构、垂直交通
     */
    CORE,

    /**
     * 服务体量（SERVICE）
     * <p>
     * 用途：服务空间、设备间
     */
    SERVICE
}
