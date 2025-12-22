package com.formacraft.common.network.packet;

import net.minecraft.network.PacketByteBuf;

/**
 * S2C 数据包：服务端 → 客户端
 * 发送构建请求的失败原因（用于替换客户端“AI 正在思考...”占位）
 */
public class ResponseBuildErrorPacket {
    public static void write(PacketByteBuf buf, String message) {
        buf.writeString(message != null ? message : "");
    }

    public static String read(PacketByteBuf buf) {
        return buf.readString();
    }
}


