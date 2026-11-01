package com.formacraft.common.network;

import com.formacraft.common.component.ComponentImportSessions;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public final class ComponentImportNetworking {
    private ComponentImportNetworking() {}
    public enum Operation { START, CHUNK, PREVIEW, CONFIRM, CANCEL, CAPTURE_START }
    public enum Reply { PREVIEW, INSTALLED, ERROR }
    private static boolean c2s, s2c;
    public static synchronized void registerC2S() {
        if (!c2s) { PayloadTypeRegistry.playC2S().register(Request.ID, Request.CODEC); c2s = true; }
    }
    public static synchronized void registerS2C() {
        if (!s2c) { PayloadTypeRegistry.playS2C().register(Response.ID, Response.CODEC); s2c = true; }
    }
    public record Request(UUID token, Operation operation, int length, int offset, byte[] data) implements CustomPayload {
        public Request { data = data == null ? new byte[0] : data.clone(); }
        public static final Id<Request> ID = new Id<>(Identifier.of("formacraft", "component_import"));
        public static final PacketCodec<PacketByteBuf, Request> CODEC = PacketCodec.of((p, b) -> {
            if (p.data.length > ComponentImportSessions.CHUNK_BYTES) throw new IllegalArgumentException("Oversized import chunk");
            b.writeUuid(p.token); b.writeEnumConstant(p.operation); b.writeVarInt(p.length); b.writeVarInt(p.offset); b.writeByteArray(p.data);
        }, b -> new Request(b.readUuid(), b.readEnumConstant(Operation.class), b.readVarInt(), b.readVarInt(), b.readByteArray(ComponentImportSessions.CHUNK_BYTES)));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    public record Response(UUID token, Reply reply, String json) implements CustomPayload {
        public static final Id<Response> ID = new Id<>(Identifier.of("formacraft", "component_import_result"));
        public static final PacketCodec<PacketByteBuf, Response> CODEC = PacketCodec.of((p, b) -> {
            b.writeUuid(p.token); b.writeEnumConstant(p.reply); b.writeString(p.json, 16384);
        }, b -> new Response(b.readUuid(), b.readEnumConstant(Reply.class), b.readString(16384)));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
}
