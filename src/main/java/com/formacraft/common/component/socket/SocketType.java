package com.formacraft.common.component.socket;

/**
 * SocketType（旧版 Socket 类型枚举）v0：兼容旧代码。
 * <p>
 * ⚠️ Deprecated：请使用新的 SocketContext / SocketShape / SocketRole 组合。
 * <p>
 * 此枚举仅用于过渡期兼容，v2 将移除。
 */
@Deprecated
public enum SocketType {
    /**
     * 门洞（旧版）
     * <p>
     * 新版等价：
     * - context = WALL
     * - shape = RECT
     * - role = PROVIDER
     * - tags = ["door"]
     */
    DOOR,

    /**
     * 窗洞（旧版）
     * <p>
     * 新版等价：
     * - context = WALL
     * - shape = RECT
     * - role = PROVIDER
     * - tags = ["window"]
     */
    WINDOW
}
