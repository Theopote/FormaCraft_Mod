package com.formacraft.common.network.packet;

import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.build.BuildingSpec;
import net.minecraft.network.PacketByteBuf;

/**
 * S2C 数据包：服务端 → 客户端
 * 发送 AI 生成的建筑规格
 */
public class ResponseBuildSpecPacket {
    public static void write(PacketByteBuf buf, BuildingSpec spec) {
        buf.writeString(JsonUtil.toJson(spec));
    }

    public static BuildingSpec read(PacketByteBuf buf) {
        return JsonUtil.fromJson(buf.readString(), BuildingSpec.class);
    }
}

