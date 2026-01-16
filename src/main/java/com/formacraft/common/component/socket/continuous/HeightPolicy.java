package com.formacraft.common.component.socket.continuous;

/**
 * HeightPolicy（高度策略）。
 */
public enum HeightPolicy {
    /** 贴地 */
    FOLLOW_TERRAIN,
    /** 台阶 */
    STEP_TERRACE,
    /** 固定高度 */
    FIXED_BASE,
    /** 自适应底座（高级） */
    ADAPTIVE_FOUNDATION
}
