package com.formacraft.common.network.packet;

import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.model.request.FormaRequestCompactor;
import net.minecraft.network.PacketByteBuf;

/**
 * C2S 数据包：客户端 → 服务端
 * 发送玩家的建筑请求
 */
public class RequestBuildPacket {
    public static void write(PacketByteBuf buf, FormaRequest req) {
        FormaRequest compact = FormaRequestCompactor.compactForNetwork(req);
        String json = JsonUtil.toJson(compact);
        if (json.length() > FormaRequestCompactor.MAX_PACKET_STRING) {
            throw new IllegalArgumentException(
                    "FormaRequest JSON too large for Minecraft packet: "
                            + json.length() + " > " + FormaRequestCompactor.MAX_PACKET_STRING
            );
        }
        buf.writeString(json);
    }

    public static FormaRequest read(PacketByteBuf buf) {
        return JsonUtil.fromJson(buf.readString(), FormaRequest.class);
    }
}

