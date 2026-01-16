package com.formacraft.common.debug;

import java.util.Set;

/**
 * DebugOverlayRenderer（调试覆盖层渲染器）
 * <p>
 * 统一接口，用于渲染不同层的调试可视化
 * <p>
 * 设计原则：
 * - 单一职责：只负责渲染，不管理状态
 * - 支持图层开关
 * - 支持客户端和服务端（服务端可以生成调试数据）
 */
public interface DebugOverlayRenderer {

    /**
     * 渲染调试覆盖层
     *
     * @param ctx 调试上下文
     * @param enabledLayers 启用的图层集合
     */
    void render(DebugContext ctx, Set<DebugLayer> enabledLayers);

    /**
     * 检查是否支持某个图层
     *
     * @param layer 图层
     * @return 是否支持
     */
    boolean supportsLayer(DebugLayer layer);
}
