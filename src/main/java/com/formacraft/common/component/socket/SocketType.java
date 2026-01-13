package com.formacraft.common.component.socket;

/**
 * SocketType（插槽类型）：用于“安装/开洞”语义约束。
 */
public enum SocketType {
    DOOR,
    WINDOW,
    BALCONY,
    /** 连接/挂接到墙段等“结构接口”。 */
    WALL,
    DECORATION,
    ROOF_ATTACHMENT
}

