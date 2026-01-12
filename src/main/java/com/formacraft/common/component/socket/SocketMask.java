package com.formacraft.common.component.socket;

/**
 * SocketMask（开洞体积）：
 * - width/height/depth 表示需要清除的体积
 * - 体积的朝向由调用方提供（通常是 socket 的 facing）
 */
public record SocketMask(int width, int height, int depth) {
    public SocketMask {
        if (width < 0 || height < 0 || depth < 0) {
            throw new IllegalArgumentException("SocketMask dimensions must be non-negative");
        }
    }
}

