package com.formacraft.common.network;

import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import com.formacraft.common.component.ComponentExportSessions;

public final class ComponentLibraryNetworking {
    private ComponentLibraryNetworking() {}
    public enum Action { DELETE, EXPORT, NEXT, CANCEL, SAVE_RECIPE, REPLAY_RECIPE, RESTORE_RECIPE }
    public enum Reply { DELETED, CHUNK, ERROR, SAVED }
    private static boolean c2s, s2c;
    public static synchronized void registerC2S() { if (!c2s) { PayloadTypeRegistry.playC2S().register(Request.ID, Request.CODEC); c2s = true; } }
    public static synchronized void registerS2C() { if (!s2c) { PayloadTypeRegistry.playS2C().register(Response.ID, Response.CODEC); s2c = true; } }
    public record Request(UUID token, Action action, String assetId, String primaryRef, int sourceIndex, String sourceRef, int offset, String options) implements CustomPayload {
        public Request(UUID token, Action action, String assetId, String primaryRef, int sourceIndex, String sourceRef, int offset) {
            this(token,action,assetId,primaryRef,sourceIndex,sourceRef,offset,"");
        }
        public static final Id<Request> ID = new Id<>(Identifier.of("formacraft", "component_library_request"));
        public static final PacketCodec<PacketByteBuf, Request> CODEC = PacketCodec.of((p,b) -> {
            b.writeUuid(p.token); b.writeEnumConstant(p.action); b.writeString(p.assetId,64); b.writeString(p.primaryRef,2048);
            b.writeVarInt(p.sourceIndex); b.writeString(p.sourceRef,2048); b.writeVarInt(p.offset); b.writeString(p.options,4096);
        }, b -> new Request(b.readUuid(),b.readEnumConstant(Action.class),b.readString(64),b.readString(2048),b.readVarInt(),b.readString(2048),b.readVarInt(),b.readString(4096)));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    public record Response(UUID token, Reply reply, int length, int offset, byte[] data, String message) implements CustomPayload {
        public Response { data = data == null ? new byte[0] : data.clone(); }
        @Override public byte[] data() { return data.clone(); }
        public static final Id<Response> ID = new Id<>(Identifier.of("formacraft", "component_library_response"));
        public static final PacketCodec<PacketByteBuf, Response> CODEC = PacketCodec.of((p,b) -> {
            if (p.data.length > ComponentExportSessions.CHUNK_BYTES) throw new IllegalArgumentException("Oversized export chunk");
            b.writeUuid(p.token); b.writeEnumConstant(p.reply); b.writeVarInt(p.length); b.writeVarInt(p.offset);
            b.writeByteArray(p.data); b.writeString(p.message,512);
        }, b -> new Response(b.readUuid(),b.readEnumConstant(Reply.class),b.readVarInt(),b.readVarInt(),b.readByteArray(ComponentExportSessions.CHUNK_BYTES),b.readString(512)));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
}
