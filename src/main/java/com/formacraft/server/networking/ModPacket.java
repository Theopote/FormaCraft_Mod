package com.formacraft.server.networking;

import com.formacraft.FormacraftMod;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public class ModPacket {
    public static final Identifier CHANNEL = Identifier.of(FormacraftMod.MOD_ID, "sync");

    // 简单的数据包实现 - 直接传递字节数组
    public record SyncPayload(byte[] data) implements CustomPayload {
        public static final CustomPayload.Id<SyncPayload> ID = new CustomPayload.Id<>(CHANNEL);
        public static final PacketCodec<PacketByteBuf, SyncPayload> CODEC = PacketCodec.of(
                // ValueFirstEncoder: (SyncPayload, PacketByteBuf) -> void
                (payload, buf) -> buf.writeByteArray(payload.data()),
                // PacketDecoder: PacketByteBuf -> SyncPayload
                buf -> new SyncPayload(buf.readByteArray())
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static void registerServer() {
        // 注册数据包类型
        PayloadTypeRegistry.playC2S().register(SyncPayload.ID, SyncPayload.CODEC);
        
        // 注册全局接收器
        ServerPlayNetworking.registerGlobalReceiver(SyncPayload.ID, (payload, context) -> {
            // handle packet (stub)
            // context.player() 获取玩家
            // context.server() 获取服务器
        });
    }

    public static void sendToServer(PacketByteBuf buf) {
        // 将 PacketByteBuf 转换为字节数组
        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), data);
        ClientPlayNetworking.send(new SyncPayload(data));
    }
}
