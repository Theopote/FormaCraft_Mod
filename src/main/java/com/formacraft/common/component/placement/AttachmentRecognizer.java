package com.formacraft.common.component.placement;

import com.formacraft.common.component.socket.SocketType;

/**
 * AttachmentRecognizer（v1，最小实现）：
 * - 在“装配/挂载”阶段，把 host 的 SocketType 映射为高层 AttachmentType
 * - 用于对 placementSpec 做兼容性过滤/打分
 *
 * 后续扩展：
 * - 从 outline / roof / edge 提取候选附着面（非 socket）
 * - 结合 SpatialContext（INTERIOR/EXTERIOR）与 edge 检测进行打分
 */
public final class AttachmentRecognizer {
    private AttachmentRecognizer() {}

    public static AttachmentType attachmentForSocketType(SocketType type) {
        if (type == null) return AttachmentType.NONE;
        return switch (type) {
            case DOOR, WINDOW -> AttachmentType.WALL_OPENING;
            case BALCONY -> AttachmentType.WALL_SURFACE; // 外挂平台：先视为贴墙面（外法线由 policy 决定）
            case ROOF_ATTACHMENT -> AttachmentType.ROOF_EDGE;
            case WALL -> AttachmentType.WALL_SURFACE;
            case DECORATION -> AttachmentType.WALL_SURFACE;
        };
    }

    /**
     * 是否允许把 placementSpec 的构件安装到 hostAttachment 上。
     * <p>
     * 规则（v1）：
     * - spec.attachment == NONE：允许（自由构件）
     * - hostAttachment == NONE：仅当不要求 attachment 才允许
     * - 精确匹配：允许
     * - CORNER：允许挂在 WALL_SURFACE（角落由 host 决定是否提供 1..2 面）
     */
    public static boolean isCompatible(ComponentPlacementSpec spec, AttachmentType hostAttachment) {
        if (spec == null) return true; // 没有 spec，保持向后兼容
        AttachmentType need = spec.attachment != null ? spec.attachment : AttachmentType.NONE;
        AttachmentType host = hostAttachment != null ? hostAttachment : AttachmentType.NONE;

        if (need == AttachmentType.NONE) return true;
        if (host == AttachmentType.NONE) {
            return spec.constraints == null || !spec.constraints.requiresAttachment;
        }
        if (need == host) return true;
        if (need == AttachmentType.CORNER && host == AttachmentType.WALL_SURFACE) return true;
        return false;
    }
}

