package com.formacraft.server.networking;

import com.formacraft.FormacraftMod;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.json.JsonUtil;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S 数据包：客户端 → 服务端
 * 发送玩家的建筑请求
 */
public record BuildRequestPacket(FormaRequest request) implements CustomPayload {
    public static final CustomPayload.Id<BuildRequestPacket> ID = 
            new CustomPayload.Id<>(Identifier.of(FormacraftMod.MOD_ID, "build_request"));
    
    public static final PacketCodec<PacketByteBuf, BuildRequestPacket> CODEC = PacketCodec.of(
            // Encoder: BuildRequestPacket -> PacketByteBuf
            (packet, buf) -> {
                String json = JsonUtil.toJson(packet.request);
                buf.writeString(json);
            },
            // Decoder: PacketByteBuf -> BuildRequestPacket
            buf -> {
                String json = buf.readString();
                FormaRequest request = JsonUtil.fromJson(json, FormaRequest.class);
                return new BuildRequestPacket(request);
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

