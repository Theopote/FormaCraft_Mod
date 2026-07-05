package com.formacraft.common.network.packet;

import net.minecraft.network.PacketByteBuf;

/**
 * S2C 数据包：服务端 → 客户端
 * 用于在聊天窗口显示 AI 追问（信息不足时多轮澄清）。
 */
public class ResponseClarificationPacket {
    public static void write(PacketByteBuf buf, String message) {
        buf.writeString(message != null ? message : "");
    }

    public static String read(PacketByteBuf buf) {
        return buf.readString();
    }
}
