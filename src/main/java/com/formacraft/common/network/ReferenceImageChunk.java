package com.formacraft.common.network;

import java.util.UUID;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** One bounded chunk of a chat reference image. */
public record ReferenceImageChunk(UUID token, int length, int offset, byte[] data) implements CustomPayload {
    public static final int MAX_BYTES = 256 * 1024;
    public static final int CHUNK_BYTES = 20 * 1024;
    public static final String REFERENCE_TYPE = "image_upload";
    public static final Id<ReferenceImageChunk> ID = new Id<>(Identifier.of("formacraft", "reference_image_chunk"));
    public static final PacketCodec<PacketByteBuf, ReferenceImageChunk> CODEC = PacketCodec.of((p, b) -> {
        b.writeUuid(p.token);
        b.writeVarInt(p.length);
        b.writeVarInt(p.offset);
        b.writeByteArray(p.data);
    }, b -> new ReferenceImageChunk(b.readUuid(), b.readVarInt(), b.readVarInt(), b.readByteArray(CHUNK_BYTES)));

    public static String mimeType(byte[] bytes) {
        if (bytes.length >= 8 && java.util.Arrays.equals(bytes, 0, 8,
                new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, 0, 8)) return "image/png";
        if (bytes.length >= 3 && bytes[0] == (byte) 255 && bytes[1] == (byte) 216
                && bytes[2] == (byte) 255) return "image/jpeg";
        return null;
    }

    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
