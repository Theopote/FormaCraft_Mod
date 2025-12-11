package com.formacraft.common.network.packet;

import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.request.FormaRequest;
import net.minecraft.network.PacketByteBuf;

/**
 * C2S 数据包：客户端 → 服务端
 * 发送玩家的建筑请求
 */
public class RequestBuildPacket {
    public static void write(PacketByteBuf buf, FormaRequest req) {
        buf.writeString(JsonUtil.toJson(req));
    }

    public static FormaRequest read(PacketByteBuf buf) {
        return JsonUtil.fromJson(buf.readString(), FormaRequest.class);
    }
}

