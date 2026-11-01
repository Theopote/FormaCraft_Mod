package com.formacraft.common.network;

import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Source metadata is paged independently from the catalog and contains no component payload. */
public final class ComponentSourceNetworking {
    private ComponentSourceNetworking() {}
    public static final int MAX_JSON = 16384;
    private static boolean c2s, s2c;
    public static synchronized void registerC2S() {
        if (!c2s) { PayloadTypeRegistry.playC2S().register(Request.ID, Request.CODEC); c2s = true; }
    }
    public static synchronized void registerS2C() {
        if (!s2c) { PayloadTypeRegistry.playS2C().register(Response.ID, Response.CODEC); s2c = true; }
    }
    public record Request(UUID token, String assetId, int index) implements CustomPayload {
        public static final Id<Request> ID = new Id<>(Identifier.of("formacraft", "component_source_request"));
        public static final PacketCodec<PacketByteBuf, Request> CODEC = PacketCodec.of(
                (p, b) -> { b.writeUuid(p.token()); b.writeString(p.assetId(), 64); b.writeVarInt(p.index()); },
                b -> new Request(b.readUuid(), b.readString(64), b.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    public record Response(UUID token, String json) implements CustomPayload {
        public static final Id<Response> ID = new Id<>(Identifier.of("formacraft", "component_source_response"));
        public static final PacketCodec<PacketByteBuf, Response> CODEC = PacketCodec.of(
                (p, b) -> { b.writeUuid(p.token()); b.writeString(p.json(), MAX_JSON); },
                b -> new Response(b.readUuid(), b.readString(MAX_JSON)));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
}
