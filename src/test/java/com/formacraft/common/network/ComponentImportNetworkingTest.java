package com.formacraft.common.network;

import com.formacraft.common.component.ComponentImportSessions;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ComponentImportNetworkingTest {
    @Test void largestChunkFitsSmallPayloadAndRoundTripsCorrelationOffsetAndContent() {
        var buffer = new PacketByteBuf(Unpooled.buffer());
        try {
            byte[] bytes = new byte[ComponentImportSessions.CHUNK_BYTES]; bytes[0] = 7;
            var request = new ComponentImportNetworking.Request(UUID.randomUUID(), ComponentImportNetworking.Operation.CHUNK, 0, 40960, bytes);
            bytes[0] = 9;
            ComponentImportNetworking.Request.CODEC.encode(buffer, request); assertTrue(buffer.readableBytes() < 32767);
            var decoded = ComponentImportNetworking.Request.CODEC.decode(buffer);
            assertEquals(request.token(), decoded.token()); assertEquals(request.operation(), decoded.operation()); assertEquals(request.offset(), decoded.offset());
            assertEquals(7, decoded.data()[0]); assertArrayEquals(request.data(), decoded.data());
            buffer.clear(); var response = new ComponentImportNetworking.Response(request.token(), ComponentImportNetworking.Reply.ERROR, "\"错误\"");
            ComponentImportNetworking.Response.CODEC.encode(buffer, response); assertEquals(response, ComponentImportNetworking.Response.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
    @Test void oversizedChunksAreRejectedOnBothEncodingAndDecoding() {
        var buffer = new PacketByteBuf(Unpooled.buffer());
        try {
            var request = new ComponentImportNetworking.Request(UUID.randomUUID(), ComponentImportNetworking.Operation.CHUNK, 0, 0, new byte[ComponentImportSessions.CHUNK_BYTES + 1]);
            assertThrows(RuntimeException.class, () -> ComponentImportNetworking.Request.CODEC.encode(buffer, request));
            buffer.clear(); buffer.writeUuid(request.token()); buffer.writeEnumConstant(request.operation()); buffer.writeVarInt(0); buffer.writeVarInt(0); buffer.writeByteArray(request.data());
            assertThrows(RuntimeException.class, () -> ComponentImportNetworking.Request.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
}
