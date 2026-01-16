package com.formacraft.common.llm.dto.structural;

/**
 * WallType（墙体类型）
 * <p>
 * 决定 Socket 行为：
 * - 哪些 SocketProfile 被启用
 * - 哪些构件允许出现
 */
public enum WallType {
    /** 外墙 */
    EXTERNAL,
    /** 内墙 */
    INTERNAL,
    /** 庭院墙 */
    COURTYARD,
    /** 用地边界（可选） */
    BOUNDARY
}
