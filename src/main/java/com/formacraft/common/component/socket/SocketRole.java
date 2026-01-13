package com.formacraft.common.component.socket;

/**
 * SocketRole（Socket 角色）v1：定义组件接口的"提供/消耗"关系。
 * <p>
 * 核心理念：
 * - PROVIDER：提供接口的组件（墙体、屋顶、地基）
 * - CONSUMER：消耗接口的组件（门、窗、阳台、装饰）
 * <p>
 * 匹配规则：
 * - PROVIDER × CONSUMER = 可匹配
 * - PROVIDER × PROVIDER = 不可匹配
 * - CONSUMER × CONSUMER = 不可匹配
 */
public enum SocketRole {
    /**
     * 提供接口（例如：墙体提供门洞、屋顶提供烟囱位）
     */
    PROVIDER,

    /**
     * 消耗接口（例如：门需要墙体洞口、烟囱需要屋顶位）
     */
    CONSUMER
}
