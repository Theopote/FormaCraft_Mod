package com.formacraft.common.component;

import com.formacraft.common.network.ComponentLibraryNetworking.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ComponentLibraryNetworkingTest {
    @Test void requestAndChunkRoundTripPreserveIdentityAndOffsets() {
        var request = new Request(UUID.randomUUID(),Action.EXPORT,"asset_a","primary",1,"source|manifest:hash",20);
        var buffer = new PacketByteBuf(Unpooled.buffer());
        try { Request.CODEC.encode(buffer,request); assertEquals(request,Request.CODEC.decode(buffer)); }
        finally { buffer.release(); }
        buffer = new PacketByteBuf(Unpooled.buffer());
        var response = new Response(request.token(),Reply.CHUNK,30,20,new byte[]{1,2},"");
        try {
            Response.CODEC.encode(buffer,response); var decoded = Response.CODEC.decode(buffer);
            assertEquals(20,decoded.offset()); assertArrayEquals(response.data(),decoded.data());
        } finally { buffer.release(); }
    }
    @Test void rejectsOversizedChunksOnBothSides() {
        var buffer = new PacketByteBuf(Unpooled.buffer());
        try {
            assertThrows(IllegalArgumentException.class,() -> Response.CODEC.encode(buffer,new Response(UUID.randomUUID(),Reply.CHUNK,30000,0,new byte[ComponentExportSessions.CHUNK_BYTES+1],"")));
            buffer.clear(); buffer.writeUuid(UUID.randomUUID()); buffer.writeEnumConstant(Reply.CHUNK); buffer.writeVarInt(30000); buffer.writeVarInt(0);
            buffer.writeByteArray(new byte[ComponentExportSessions.CHUNK_BYTES+1]); buffer.writeString("");
            assertThrows(RuntimeException.class,() -> Response.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
}
