package com.formacraft.common.component.socket;

import com.formacraft.common.component.placement.ComponentPlacementSpec;
import com.formacraft.common.component.variant.ComponentVariant;

import java.util.ArrayList;
import java.util.List;

/**
 * SocketMatcher（插槽匹配器）：真正的"合法性裁判"。
 * <p>
 * 这是 ComponentTool + AI 共用的核心逻辑。
 * <p>
 * 核心思想：
 * - 不是构件有方向，而是"它能附着在什么地方"
 * - AI 永远不碰 Socket
 * - Socket 是世界几何 + 建筑语义自动推导的
 */
public final class SocketMatcher {
    private SocketMatcher() {}

    /**
     * 匹配构件与 Socket
     * 
     * @param component 构件变体
     * @param sockets 可用的 Socket 列表
     * @return 匹配的 Socket 列表（合法的放置位置）
     */
    public static List<Socket> match(
            ComponentVariant component,
            List<Socket> sockets
    ) {
        if (component == null || component.base == null || sockets == null) {
            return List.of();
        }

        ComponentPlacementSpec spec = component.base.placementSpec;
        if (spec == null) {
            // 如果没有 placementSpec，返回所有未占用的 Socket
            return sockets.stream()
                    .filter(s -> !s.occupied)
                    .toList();
        }

        List<Socket> result = new ArrayList<>();

        for (Socket s : sockets) {
            // 1. 检查是否被占用
            if (s.occupied) {
                continue;
            }

            // 2. 检查 Socket 类型是否允许
            if (spec.allowedSockets != null && !spec.allowedSockets.isEmpty()) {
                if (!spec.allowedSockets.contains(s.type)) {
                    continue;
                }
            }

            // 3. 检查是否必须在外侧
            if (spec.requireExterior && !s.isExterior()) {
                continue;
            }

            // 4. 检查是否必须嵌入（门 / 窗）
            if (spec.requiresOpening && s.type != SocketType.WALL_OPENING) {
                continue;
            }

            // 5. 检查是否必须在边缘
            if (spec.requireEdge && s.type != SocketType.EDGE_OUTER) {
                continue;
            }

            // 6. 检查是否禁止内部（如果 spec 有 forbidInterior）
            if (spec.constraints != null && spec.constraints.forbidInterior && !s.isExterior()) {
                continue;
            }

            // 通过所有检查，添加到结果
            result.add(s);
        }

        return result;
    }

    /**
     * 匹配构件与 Socket（使用 ComponentPlacementSpec）
     * 
     * @param spec 构件放置规格
     * @param sockets 可用的 Socket 列表
     * @return 匹配的 Socket 列表（合法的放置位置）
     */
    public static List<Socket> match(
            ComponentPlacementSpec spec,
            List<Socket> sockets
    ) {
        if (spec == null || sockets == null) {
            return List.of();
        }

        List<Socket> result = new ArrayList<>();

        for (Socket s : sockets) {
            // 1. 检查是否被占用
            if (s.occupied) {
                continue;
            }

            // 2. 检查 Socket 类型是否允许
            if (spec.allowedSockets != null && !spec.allowedSockets.isEmpty()) {
                if (!spec.allowedSockets.contains(s.type)) {
                    continue;
                }
            }

            // 3. 检查是否必须在外侧
            if (spec.requireExterior && !s.isExterior()) {
                continue;
            }

            // 4. 检查是否必须嵌入（门 / 窗）
            if (spec.requiresOpening && s.type != SocketType.WALL_OPENING) {
                continue;
            }

            // 5. 检查是否必须在边缘
            if (spec.requireEdge && s.type != SocketType.EDGE_OUTER) {
                continue;
            }

            // 6. 检查是否禁止内部（如果 spec 有 forbidInterior）
            if (spec.constraints != null && spec.constraints.forbidInterior && !s.isExterior()) {
                continue;
            }

            // 通过所有检查，添加到结果
            result.add(s);
        }

        return result;
    }
}
