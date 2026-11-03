package com.formacraft.common.network;

import java.util.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Dedicated bounded game transport, independent from HTTP and component package transfer. */
public final class CommunityTaskNetworking {
    private CommunityTaskNetworking() {}
    public static final int CHUNK_BYTES = 8 * 1024, MAX_REQUEST = 32 * 1024, MAX_RESULT = 128 * 1024;
    public enum Operation { OPEN, SEARCH_BEGIN, SEARCH_CHUNK, SEARCH_SUBMIT, DOWNLOAD, POLL, RESULT_CHUNK, CANCEL, CLOSE, INSTALL_PREVIEW, INSTALL_CONFIRM }
    public enum Reply { OPENED, ACK, STATUS, CHUNK, CLOSED, ERROR }
    private static boolean c2s, s2c;
    public static synchronized void registerC2S() { if (!c2s) { PayloadTypeRegistry.playC2S().register(Request.ID,Request.CODEC); c2s = true; } }
    public static synchronized void registerS2C() { if (!s2c) { PayloadTypeRegistry.playS2C().register(Response.ID,Response.CODEC); s2c = true; } }
    public record View(UUID taskId,String kind,String status,String code,String receiptId,UUID catalogTaskId,
                       long deadline,long catalogExpiresAt,String authorityId) {
        public View {
            if (!Set.of("","SEARCH","DOWNLOAD","PREVIEW","INSTALL").contains(kind) || !Set.of("IDLE","QUEUED","RUNNING","CACHING","SUCCEEDED","FAILED","CANCELLED").contains(status)
                    || !safeCode(code) || receiptId == null || !receiptId.isEmpty() && !receiptId.matches("receipt_[0-9a-f]{64}")
                    || authorityId == null || !authorityId.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}") || deadline < 0 || catalogExpiresAt < 0
                    || (status.equals("IDLE") ? taskId != null || !kind.isEmpty() : taskId == null || kind.isEmpty())
                    || !receiptId.isEmpty() && !(status.equals("SUCCEEDED") && Set.of("DOWNLOAD","PREVIEW","INSTALL").contains(kind)))
                throw new IllegalArgumentException("Invalid community task view");
        }
    }
    public record Request(UUID token,Operation operation,UUID sessionId,UUID subjectId,String releaseId,int offset,int length,byte[] data,boolean resumable) implements CustomPayload {
        public Request {
            Objects.requireNonNull(token); Objects.requireNonNull(operation); data = data == null ? new byte[0] : data.clone();
            if (releaseId == null || !releaseId.isEmpty() && !releaseId.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}")
                    || offset < 0 || offset > MAX_RESULT || length < 0 || length > MAX_RESULT || data.length > CHUNK_BYTES) throw new IllegalArgumentException("Invalid community request bounds");
        }
        @Override public byte[] data() { return data.clone(); }
        public static final Id<Request> ID = new Id<>(Identifier.of("formacraft","community_task_request"));
        public static final PacketCodec<PacketByteBuf,Request> CODEC = PacketCodec.of((p,b) -> {
            b.writeUuid(p.token); b.writeEnumConstant(p.operation); uuid(b,p.sessionId); uuid(b,p.subjectId); b.writeString(p.releaseId,128);
            b.writeVarInt(p.offset); b.writeVarInt(p.length); b.writeByteArray(p.data); b.writeBoolean(p.resumable);
        },b -> new Request(b.readUuid(),b.readEnumConstant(Operation.class),uuid(b),uuid(b),b.readString(128),b.readVarInt(),b.readVarInt(),b.readByteArray(CHUNK_BYTES),b.readBoolean()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    public record Response(UUID token,Reply reply,UUID sessionId,UUID subjectId,View view,int offset,int length,String sha256,byte[] data,String code) implements CustomPayload {
        public Response {
            Objects.requireNonNull(token); Objects.requireNonNull(reply); data = data == null ? new byte[0] : data.clone();
            if (offset < 0 || offset > MAX_RESULT || length < 0 || length > MAX_RESULT || data.length > CHUNK_BYTES
                    || sha256 == null || !sha256.isEmpty() && !sha256.matches("[0-9a-f]{64}") || !safeCode(code)) throw new IllegalArgumentException("Invalid community response bounds");
        }
        @Override public byte[] data() { return data.clone(); }
        public static final Id<Response> ID = new Id<>(Identifier.of("formacraft","community_task_response"));
        public static final PacketCodec<PacketByteBuf,Response> CODEC = PacketCodec.of((p,b) -> {
            b.writeUuid(p.token); b.writeEnumConstant(p.reply); uuid(b,p.sessionId); uuid(b,p.subjectId); b.writeBoolean(p.view != null);
            if (p.view != null) {
                var v = p.view; uuid(b,v.taskId); b.writeString(v.kind,16); b.writeString(v.status,16); b.writeString(v.code,64); b.writeString(v.receiptId,72);
                uuid(b,v.catalogTaskId); b.writeLong(v.deadline); b.writeLong(v.catalogExpiresAt); b.writeString(v.authorityId,128);
            }
            b.writeVarInt(p.offset); b.writeVarInt(p.length); b.writeString(p.sha256,64); b.writeByteArray(p.data); b.writeString(p.code,64);
        },b -> {
            UUID token = b.readUuid(); Reply reply = b.readEnumConstant(Reply.class); UUID session = uuid(b), subject = uuid(b); View view = null;
            if (b.readBoolean()) view = new View(uuid(b),b.readString(16),b.readString(16),b.readString(64),b.readString(72),uuid(b),b.readLong(),b.readLong(),b.readString(128));
            return new Response(token,reply,session,subject,view,b.readVarInt(),b.readVarInt(),b.readString(64),b.readByteArray(CHUNK_BYTES),b.readString(64));
        });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    private static boolean safeCode(String value) { return value != null && (value.isEmpty() || value.matches("[a-z][a-z0-9_]{0,63}")); }
    private static void uuid(PacketByteBuf buffer,UUID value) { buffer.writeBoolean(value != null); if (value != null) buffer.writeUuid(value); }
    private static UUID uuid(PacketByteBuf buffer) { return buffer.readBoolean() ? buffer.readUuid() : null; }
}
