package com.formacraft.client.tool.socket;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.component.socket.SocketMatcher;
import com.formacraft.common.component.socket.SocketRegistry;
import com.formacraft.common.component.variant.ComponentVariant;
import com.formacraft.common.buildcontext.BuildContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Box;

import java.util.List;

/**
 * SocketHighlighter（插槽高亮器）：ComponentTool 如何利用 Socket。
 * <p>
 * 行为：
 * - 玩家选中一个 Component
 * - 系统：扫描世界 → SocketProvider → SocketMatcher 过滤 → 高亮合法 socket 区域
 * - 非法位置：红色 / 不可点击
 * <p>
 * 这一步会让 ComponentTool 看起来非常专业。
 */
public final class SocketHighlighter {
    private SocketHighlighter() {}

    /**
     * 获取合法的 Socket 列表（用于高亮）
     * 
     * @param component 构件定义
     * @param variant 构件变体（可选）
     * @param ctx 构建上下文
     * @return 合法的 Socket 列表
     */
    public static List<Socket> getValidSockets(
            ComponentDefinition component,
            ComponentVariant variant,
            BuildContext ctx
    ) {
        if (component == null || ctx == null) {
            return List.of();
        }

        // 1. 获取所有 Socket
        List<Socket> allSockets = SocketRegistry.getAllSockets(ctx);

        // 2. 创建变体（如果没有提供）
        if (variant == null) {
            // 使用默认变体（不缩放、不镜像）
            variant = new ComponentVariant(component);
        }

        // 3. 匹配合法的 Socket
        return SocketMatcher.match(variant, allSockets);
    }

    /**
     * 渲染 Socket 高亮（在 ComponentTool 中使用）
     * 
     * @param client Minecraft 客户端
     * @param sockets 要渲染的 Socket 列表
     */
    public static void renderHighlights(MinecraftClient client, List<Socket> sockets) {
        if (client == null || sockets == null || sockets.isEmpty()) {
            return;
        }

        // TODO: 实现渲染逻辑
        // 1. 遍历 sockets
        // 2. 对每个 socket.bounds 渲染高亮框
        // 3. 合法位置：绿色半透明
        // 4. 非法位置：红色半透明（如果有）
    }
}
