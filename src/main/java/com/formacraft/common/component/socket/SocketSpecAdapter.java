package com.formacraft.common.component.socket;

import com.formacraft.common.component.archetype.AttachmentSpec;
import com.formacraft.common.component.placement.ComponentPlacementSpec;

import java.util.HashSet;
import java.util.Set;

/**
 * SocketSpecAdapter（插槽规格适配器）：从 AttachmentSpec 和 ComponentPlacementSpec 推断 Socket 需求。
 * <p>
 * 核心思想：
 * - 不是构件有方向，而是"它能附着在什么地方"
 * - 从 AttachmentSpec 自动推断 allowedSockets
 */
public final class SocketSpecAdapter {
    private SocketSpecAdapter() {}

    /**
     * 从 AttachmentSpec 推断 SocketType 集合
     */
    public static Set<SocketType> inferSocketTypes(AttachmentSpec attachment) {
        Set<SocketType> socketTypes = new HashSet<>();

        if (attachment == null) {
            socketTypes.add(SocketType.FREE_ATTACH);
            return socketTypes;
        }

        // 根据 AttachmentType 推断
        switch (attachment.type) {
            case SURFACE -> {
                // SURFACE 需要根据 allowedContexts 进一步判断
                if (attachment.allowedContexts != null) {
                    for (var ctx : attachment.allowedContexts) {
                        switch (ctx) {
                            case WALL -> socketTypes.add(SocketType.WALL_SURFACE);
                            case ROOF -> socketTypes.add(SocketType.ROOF_SLOPE);
                            case FLOOR -> socketTypes.add(SocketType.FLOOR_SURFACE);
                            default -> {}
                        }
                    }
                } else {
                    socketTypes.add(SocketType.WALL_SURFACE); // 默认
                }
            }
            case EDGE -> {
                socketTypes.add(SocketType.EDGE_OUTER);
            }
            case POINT -> {
                socketTypes.add(SocketType.COLUMN_TOP);
                socketTypes.add(SocketType.FLOOR_SURFACE);
            }
            case VOLUME -> {
                socketTypes.add(SocketType.FREE_ATTACH);
            }
        }

        // 如果 attachment 需要开口（门/窗），添加 WALL_OPENING
        // 这里需要根据实际业务逻辑判断
        // 简化处理：如果 type 是 SURFACE 且 allowedContexts 包含 WALL，可能是门/窗
        if (attachment.type == com.formacraft.common.component.archetype.AttachmentType.SURFACE &&
            attachment.allowedContexts != null &&
            attachment.allowedContexts.contains(com.formacraft.common.component.archetype.ContextType.WALL)) {
            // 不自动添加 WALL_OPENING，需要显式声明
        }

        if (socketTypes.isEmpty()) {
            socketTypes.add(SocketType.FREE_ATTACH); // 默认
        }

        return socketTypes;
    }

    /**
     * 从 ComponentPlacementSpec 推断 Socket 需求
     */
    public static void populateSocketSpec(ComponentPlacementSpec spec) {
        if (spec == null) {
            return;
        }

        // 如果已经有 allowedSockets，不覆盖
        if (spec.allowedSockets != null && !spec.allowedSockets.isEmpty()) {
            return;
        }

        if (spec.allowedSockets == null) {
            spec.allowedSockets = new HashSet<>();
        }

        // 根据 AttachmentType 推断
        switch (spec.attachment) {
            case WALL_OPENING -> {
                spec.allowedSockets.add(SocketType.WALL_OPENING);
                spec.requiresOpening = true;
            }
            case WALL_SURFACE -> {
                spec.allowedSockets.add(SocketType.WALL_SURFACE);
            }
            case EDGE -> {
                spec.allowedSockets.add(SocketType.EDGE_OUTER);
                spec.requireEdge = true;
            }
            case ROOF_SURFACE -> {
                spec.allowedSockets.add(SocketType.ROOF_SLOPE);
            }
            case ROOF_EDGE -> {
                spec.allowedSockets.add(SocketType.ROOF_RIDGE);
            }
            case ROOF_RIDGE -> {
                spec.allowedSockets.add(SocketType.ROOF_RIDGE);
            }
            case FLOOR -> {
                spec.allowedSockets.add(SocketType.FLOOR_SURFACE);
            }
            case CORNER -> {
                spec.allowedSockets.add(SocketType.EDGE_OUTER);
                spec.allowMultiAttach = true;
            }
            case NONE -> {
                spec.allowedSockets.add(SocketType.FREE_ATTACH);
            }
        }

        // 根据 hasInteriorExterior 和 constraints 设置 requireExterior
        if (spec.hasInteriorExterior && spec.constraints != null && spec.constraints.forbidInterior) {
            spec.requireExterior = true;
        }
    }
}
