package com.formacraft.common.component.socket.continuous;

/**
 * CornerHandling（转角处理策略）。
 */
public enum CornerHandling {
    /** 切断（直角） */
    CUT,
    /** 45° 斜接 */
    MITER,
    /** 转角插柱 */
    PILLAR,
    /** 平滑过渡（回廊） */
    SMOOTH
}
