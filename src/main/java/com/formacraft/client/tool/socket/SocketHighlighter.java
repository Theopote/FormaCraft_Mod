package com.formacraft.client.tool.socket;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.component.socket.SocketProviders;
import com.formacraft.common.component.socket.SocketQueryContext;
import com.formacraft.common.component.socket.SocketQueryContextBuilder;
import com.formacraft.common.component.socket.match.SocketMatchResult;
import com.formacraft.common.component.variant.ComponentVariant;
import com.formacraft.client.tool.OutlineTool;
import com.formacraft.client.tool.PathTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.tool.ToolWorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.stream.Collectors;

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
     * @param focus 焦点位置（鼠标 hit 或 anchor）
     * @return 合法的 Socket 列表（带评分）
     */
    public static List<SocketMatchResult> getValidSockets(
            ComponentDefinition component,
            ComponentVariant variant,
            Vec3d focus
    ) {
        if (component == null || component.placementSpec == null) {
            return List.of();
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) {
            return List.of();
        }

        // 1. 从工具状态创建查询上下文
        SocketQueryContext ctx = SocketQueryContextBuilder.fromTools(
                SelectionTool.INSTANCE,
                OutlineTool.INSTANCE,
                PathTool.INSTANCE,
                focus
        );

        // 2. 收集所有 Socket
        List<Socket> allSockets = SocketProviders.collect(client.world, ctx);

        // 3. 使用新的详细匹配逻辑
        return com.formacraft.common.component.socket.match.SocketMatcher.match(
                allSockets, component.placementSpec, focus
        );
    }

    /**
     * 获取合法的 Socket 列表（简化版，只返回 Socket）
     * 
     * @param component 构件定义
     * @param variant 构件变体（可选）
     * @param focus 焦点位置
     * @return 合法的 Socket 列表
     */
    public static List<Socket> getValidSocketsSimple(
            ComponentDefinition component,
            ComponentVariant variant,
            Vec3d focus
    ) {
        return getValidSockets(component, variant, focus).stream()
                .filter(r -> r.valid)
                .map(r -> r.socket)
                .collect(Collectors.toList());
    }

    /**
     * 渲染 Socket 高亮（在 ComponentTool 中使用）
     * 
     * @param ctx 世界渲染上下文
     * @param results 匹配结果列表（包含评分和原因）
     */
    public static void renderHighlights(ToolWorldRenderContext ctx, List<SocketMatchResult> results) {
        if (ctx == null || results == null || results.isEmpty()) {
            return;
        }

        // 遍历 results，渲染每个 Socket 的高亮框
        for (SocketMatchResult result : results) {
            if (result == null || result.socket == null || result.socket.bounds == null) {
                continue;
            }

            // 获取 Socket 的边界框
            Box bounds = result.socket.bounds;

            // 计算颜色（根据是否合法）
            float r, g, b, a;
            if (result.valid) {
                // 合法位置：绿色半透明
                r = 0.2f;
                g = 0.95f;
                b = 0.25f;
                a = 0.4f;
            } else {
                // 非法位置：红色半透明
                r = 1.0f;
                g = 0.25f;
                b = 0.25f;
                a = 0.3f;
            }

            // 渲染高亮框（相对于摄像机位置）
            Box box = bounds.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
            box = box.expand(0.01); // 稍微扩展以避免 z-fighting
            VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, r, g, b, a);
        }
    }
}
