package com.formacraft.common.network;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.json.JsonUtil;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import com.formacraft.FormacraftMod;

/**
 * C2S 数据包：客户端 → 服务端
 * 玩家确认建造后发送此数据包
 */
public record ConfirmBuildPacket(BuildingSpec spec, int[] origin) implements CustomPayload {
    public static final CustomPayload.Id<ConfirmBuildPacket> ID = 
            new CustomPayload.Id<>(Identifier.of(FormacraftMod.MOD_ID, "confirm_build"));
    
    public static final PacketCodec<PacketByteBuf, ConfirmBuildPacket> CODEC = PacketCodec.of(
            (packet, buf) -> {
                String specJson = JsonUtil.toJson(packet.spec);
                buf.writeString(specJson);
                buf.writeIntArray(packet.origin);
            },
            buf -> {
                String specJson = buf.readString();
                BuildingSpec spec = JsonUtil.fromJson(specJson, BuildingSpec.class);
                int[] origin = buf.readIntArray();
                return new ConfirmBuildPacket(spec, origin);
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

