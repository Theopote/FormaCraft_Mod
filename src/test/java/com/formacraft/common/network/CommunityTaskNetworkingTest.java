package com.formacraft.common.network;

import com.formacraft.common.network.CommunityTaskNetworking.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommunityTaskNetworkingTest {
    @Test void requestChunksRoundTripWithinSmallPacketBudgetAndDefensivelyCopy() {
        byte[] bytes = new byte[CommunityTaskNetworking.CHUNK_BYTES]; bytes[0] = 7;
        var request = new Request(UUID.randomUUID(),Operation.SEARCH_CHUNK,UUID.randomUUID(),UUID.randomUUID(),"",0,0,bytes,false);
        bytes[0] = 9; request.data()[0] = 8; assertEquals(7,request.data()[0]);
        var buffer = new PacketByteBuf(Unpooled.buffer());
        try {
            Request.CODEC.encode(buffer,request); assertTrue(buffer.readableBytes() < 10*1024);
            var decoded = Request.CODEC.decode(buffer); assertEquals(request.token(),decoded.token()); assertEquals(request.sessionId(),decoded.sessionId());
            assertArrayEquals(request.data(),decoded.data()); assertEquals(Operation.SEARCH_CHUNK,decoded.operation());
        } finally { buffer.release(); }
    }
    @Test void statusAndChunkRepliesRetainBoundedIdentityWithoutRawPackagePayload() {
        var task = UUID.randomUUID(); var view = new View(task,"DOWNLOAD","SUCCEEDED","","receipt_"+"1".repeat(64),UUID.randomUUID(),1,2,"test");
        var response = new Response(UUID.randomUUID(),Reply.STATUS,UUID.randomUUID(),task,view,0,0,"",null,"");
        var buffer = new PacketByteBuf(Unpooled.buffer());
        try {
            Response.CODEC.encode(buffer,response); assertTrue(buffer.readableBytes() < 1024);
            var decoded = Response.CODEC.decode(buffer); assertEquals(view,decoded.view()); assertEquals(response.token(),decoded.token()); assertEquals(0,decoded.data().length);
            buffer.clear(); var chunk = new Response(UUID.randomUUID(),Reply.CHUNK,response.sessionId(),task,null,8192,131072,"2".repeat(64),new byte[8192],"");
            Response.CODEC.encode(buffer,chunk); assertTrue(buffer.readableBytes() < 10*1024); assertArrayEquals(chunk.data(),Response.CODEC.decode(buffer).data());
        } finally { buffer.release(); }
    }
    @Test void oversizedEncodedArraysUnknownOperationsAndInvalidViewsAreRejected() {
        assertThrows(IllegalArgumentException.class,() -> new Request(UUID.randomUUID(),Operation.SEARCH_CHUNK,null,null,"",0,0,new byte[8193],false));
        assertThrows(IllegalArgumentException.class,() -> new Response(UUID.randomUUID(),Reply.CHUNK,null,null,null,0,131073,"",null,""));
        assertThrows(IllegalArgumentException.class,() -> new View(null,"SEARCH","RUNNING","","",null,0,0,"test"));
        var buffer = new PacketByteBuf(Unpooled.buffer());
        try {
            buffer.writeUuid(UUID.randomUUID()); buffer.writeVarInt(999); assertThrows(RuntimeException.class,() -> Request.CODEC.decode(buffer));
            buffer.clear(); buffer.writeUuid(UUID.randomUUID()); buffer.writeEnumConstant(Operation.SEARCH_CHUNK); buffer.writeBoolean(false); buffer.writeBoolean(false);
            buffer.writeString(""); buffer.writeVarInt(0); buffer.writeVarInt(0); buffer.writeByteArray(new byte[8193]); buffer.writeBoolean(false);
            assertThrows(RuntimeException.class,() -> Request.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
}
