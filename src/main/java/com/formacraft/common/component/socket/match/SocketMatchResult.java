package com.formacraft.common.component.socket.match;

import com.formacraft.common.component.socket.Socket;

import java.util.EnumSet;

/**
 * SocketMatchResult（Socket 匹配结果）。
 * <p>
 * 包含：
 * - Socket
 * - 是否合法
 * - 综合评分
 * - 失败原因（debug / AI 解释用）
 */
public final class SocketMatchResult {
    public final Socket socket;
    public final boolean valid;
    public final SocketMatchScore score;
    public final EnumSet<SocketMatchReason> reasons;

    public SocketMatchResult(Socket socket,
                             boolean valid,
                             SocketMatchScore score,
                             EnumSet<SocketMatchReason> reasons) {
        this.socket = socket;
        this.valid = valid;
        this.score = score;
        this.reasons = reasons;
    }
}
