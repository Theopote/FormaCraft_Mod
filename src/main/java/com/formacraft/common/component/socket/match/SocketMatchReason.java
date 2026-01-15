package com.formacraft.common.component.socket.match;

/**
 * SocketMatchReason（Socket 匹配原因）：可解释性非常重要。
 * <p>
 * 用于解释为什么一个 Socket 匹配成功或失败，便于 debug 和 AI 解释。
 */
public enum SocketMatchReason {
    /** Socket 类型不匹配 */
    TYPE_MISMATCH,

    /** 上下文不匹配（interior/exterior） */
    CONTEXT_MISMATCH,

    /** Attachment 不允许 */
    ATTACHMENT_NOT_ALLOWED,

    /** Facing 不允许 */
    FACING_NOT_ALLOWED,

    /** 尺寸太小 */
    SIZE_TOO_SMALL,

    /** 尺寸太大 */
    SIZE_TOO_LARGE,

    /** 内部不允许 */
    INTERIOR_NOT_ALLOWED,

    /** 外部不允许 */
    EXTERIOR_NOT_ALLOWED,

    /** Socket 已被占用 */
    OCCUPIED,

    /** 匹配成功 */
    OK
}
