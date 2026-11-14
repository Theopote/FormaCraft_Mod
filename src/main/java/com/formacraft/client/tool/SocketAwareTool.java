package com.formacraft.client.tool;

import com.formacraft.common.component.model.ComponentPrototype;

/**
 * SocketAwareTool（Socket 感知工具接口）v1：支持 Socket 高亮的工具。
 * <p>
 * 职责：
 * - 告诉渲染系统"当前激活的组件是什么"
 * - 渲染系统根据组件的 Consumer Socket 高亮世界中匹配的 Provider Socket
 * <p>
 * 使用场景：
 * - ComponentTool：放置组件时，高亮可用的墙面/边缘/屋顶
 * - 未来其他工具（例如：SocketTool、MountTool 等）
 * <p>
 * 候选 Socket 可由 SocketProviders.collect 从工具上下文收集；已放置实例的世界扫描仍待实现。
 */
public interface SocketAwareTool {
    /**
     * 获取当前激活的组件原型（用于 Socket 匹配）。
     * <p>
     * 返回：
     * - ComponentPrototype：当前激活的原型（包含 sockets 列表）
     * - null：无激活组件（不渲染高亮）
     */
    ComponentPrototype getActivePrototype();

    /**
     * 是否启用 Socket 高亮（可选，默认 true）。
     * <p>
     * 作用：
     * - 允许工具临时禁用高亮（例如：玩家按住 Shift 时）
     */
    default boolean isSocketHighlightEnabled() {
        return true;
    }
}
