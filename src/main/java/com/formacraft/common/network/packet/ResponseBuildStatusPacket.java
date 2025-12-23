package com.formacraft.common.network.packet;

import net.minecraft.network.PacketByteBuf;

/**
 * S2C 数据包：服务端 → 客户端
 * 用于在聊天窗口显示“当前进度/状态”，避免用户黑盒等待。
 */
public class ResponseBuildStatusPacket {
    public static void write(PacketByteBuf buf, String message) {
        buf.writeString(message != null ? message : "");
    }

    public static String read(PacketByteBuf buf) {
        return buf.readString();
    }
}


