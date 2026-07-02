package com.formacraft.server.networking;

import com.formacraft.FormacraftMod;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Legacy networking stub (sync channel only).
 * <p>
 * Build requests use {@link com.formacraft.common.network.FormaCraftNetworking}
 * ({@code formacraft:request_build}). The old {@code build_request} channel is no longer registered.
 *
 * @deprecated Use {@link com.formacraft.common.network.FormaCraftNetworking} for all new networking.
 */
@Deprecated
public class ModPacket {
    public static final Identifier CHANNEL = Identifier.of(FormacraftMod.MOD_ID, "sync");

    public record SyncPayload(byte[] data) implements CustomPayload {
        public static final CustomPayload.Id<SyncPayload> ID = new CustomPayload.Id<>(CHANNEL);
        public static final PacketCodec<PacketByteBuf, SyncPayload> CODEC = PacketCodec.of(
                (payload, buf) -> buf.writeByteArray(payload.data()),
                buf -> new SyncPayload(buf.readByteArray())
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static void registerServer() {
        PayloadTypeRegistry.playC2S().register(SyncPayload.ID, SyncPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SyncPayload.ID, (payload, context) -> {
            // legacy stub — no-op
        });
    }

    @Deprecated
    public static void sendToServer(PacketByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), data);
        ClientPlayNetworking.send(new SyncPayload(data));
    }
}
