package com.formacraft.server.networking;

import com.formacraft.FormacraftMod;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.json.JsonUtil;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C 数据包：服务端 → 客户端（遗留，未使用）。
 *
 * @deprecated 使用 {@link com.formacraft.common.network.FormaCraftNetworking} 的 S2C 数据包代替。
 */
@Deprecated(forRemoval = true)
public record BuildResponsePacket(BuildingSpec spec) implements CustomPayload {
    public static final CustomPayload.Id<BuildResponsePacket> ID = 
            new CustomPayload.Id<>(Identifier.of(FormacraftMod.MOD_ID, "build_response"));
    
    public static final PacketCodec<PacketByteBuf, BuildResponsePacket> CODEC = PacketCodec.of(
            // Encoder: BuildResponsePacket -> PacketByteBuf
            (packet, buf) -> {
                String json = JsonUtil.toJson(packet.spec);
                buf.writeString(json);
            },
            // Decoder: PacketByteBuf -> BuildResponsePacket
            buf -> {
                String json = buf.readString();
                BuildingSpec spec = JsonUtil.fromJson(json, BuildingSpec.class);
                return new BuildResponsePacket(spec);
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

