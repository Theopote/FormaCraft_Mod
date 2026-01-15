package com.formacraft.common.component.archetype;

import java.util.Set;

/**
 * SocketSpec（对接规格）：定义构件的"接口"，用于构件拼装。
 * <p>
 * Socket = 构件的"接口"，AI 可以根据 socket 自动推断"这里该用什么构件"。
 */
public class SocketSpec {
    /**
     * Socket 类型（例如：wall.opening, roof.edge, edge.linear）
     */
    public String socketType;

    /**
     * 允许连接的 socket 类型
     */
    public Set<String> compatibleWith;

    /**
     * 是否自动对齐
     */
    public boolean autoAlign = true;

    /**
     * 创建门的 SocketSpec
     */
    public static SocketSpec forDoor() {
        SocketSpec spec = new SocketSpec();
        spec.socketType = "wall.opening";
        spec.compatibleWith = Set.of("wall.opening");
        spec.autoAlign = true;
        return spec;
    }

    /**
     * 创建窗的 SocketSpec
     */
    public static SocketSpec forWindow() {
        SocketSpec spec = new SocketSpec();
        spec.socketType = "wall.opening";
        spec.compatibleWith = Set.of("wall.opening");
        spec.autoAlign = true;
        return spec;
    }

    /**
     * 创建栏杆的 SocketSpec
     */
    public static SocketSpec forRailing() {
        SocketSpec spec = new SocketSpec();
        spec.socketType = "edge.linear";
        spec.compatibleWith = Set.of("edge.linear", "edge.corner");
        spec.autoAlign = true;
        return spec;
    }
}
