package com.formacraft.common.component.socket;

import com.formacraft.common.component.placement.ComponentPlacementSpec;
import com.formacraft.common.component.variant.ComponentVariant;
import com.formacraft.common.component.socket.match.SocketMatchResult;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SocketMatcher（插槽匹配器）：真正的"合法性裁判"。
 * <p>
 * 这是 ComponentTool + AI 共用的核心逻辑。
 * <p>
 * 核心思想：
 * - 不是构件有方向，而是"它能附着在什么地方"
 * - AI 永远不碰 Socket
 * - Socket 是世界几何 + 建筑语义自动推导的
 * <p>
 * 注意：新的详细匹配逻辑在 com.formacraft.common.component.socket.match.SocketMatcher 中。
 * 这个类保留用于向后兼容。
 */
public final class SocketMatcher {
    private SocketMatcher() {}

    /**
     * 匹配构件与 Socket（向后兼容方法）
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

        // 使用新的详细匹配逻辑
        List<SocketMatchResult> results = com.formacraft.common.component.socket.match.SocketMatcher.match(
                sockets, spec, null
        );

        // 只返回合法的 Socket
        return results.stream()
                .filter(r -> r.valid)
                .map(r -> r.socket)
                .collect(Collectors.toList());
    }

    /**
     * 匹配构件与 Socket（使用 ComponentPlacementSpec，向后兼容方法）
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

        // 使用新的详细匹配逻辑
        List<SocketMatchResult> results = com.formacraft.common.component.socket.match.SocketMatcher.match(
                sockets, spec, null
        );

        // 只返回合法的 Socket
        return results.stream()
                .filter(r -> r.valid)
                .map(r -> r.socket)
                .collect(Collectors.toList());
    }
}
