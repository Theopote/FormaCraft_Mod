package com.formacraft.common.component;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ComponentCatalogTransferTest {
    @Test void largeUnicodeCatalogPublishesOnlyAfterAllChunks() throws Exception {
        String json = "{\"name\":\""+"古典窗饰".repeat(20000)+"\"}";
        byte[] bytes = ComponentCatalogTransfer.encode(json); var receiver = new ComponentCatalogTransfer.Receiver(System::nanoTime);
        String result = null;
        for (int offset = 0; offset < bytes.length;) {
            var chunk = ComponentCatalogTransfer.chunk(1,bytes,offset); result = receiver.accept(chunk); offset += chunk.data().length;
            if (offset < bytes.length) { assertNull(result); assertTrue(receiver.active()); }
        }
        assertEquals(json,result); assertFalse(receiver.active());
        assertNull(receiver.accept(ComponentCatalogTransfer.chunk(1,bytes,0)));
    }
    @Test void newerRevisionSupersedesOldAndStaleChunksCannotReplaceIt() throws Exception {
        byte[] bytes = ComponentCatalogTransfer.encode("x".repeat(50000)); var receiver = new ComponentCatalogTransfer.Receiver(System::nanoTime);
        receiver.accept(ComponentCatalogTransfer.chunk(1,bytes,0));
        assertEquals("{}",receiver.accept(ComponentCatalogTransfer.chunk(2,ComponentCatalogTransfer.encode("{}"),0)));
        assertNull(receiver.accept(ComponentCatalogTransfer.chunk(1,bytes,ComponentCatalogTransfer.CHUNK_BYTES)));
        assertNull(receiver.accept(ComponentCatalogTransfer.chunk(1,bytes,0)));
    }
    @Test void rejectsGapsOversizedDeclarationsAndInvalidEncodingAndExpires() throws Exception {
        var time = new AtomicLong(); var receiver = new ComponentCatalogTransfer.Receiver(time::get);
        byte[] bytes = ComponentCatalogTransfer.encode("x".repeat(50000)); receiver.accept(ComponentCatalogTransfer.chunk(1,bytes,0));
        assertThrows(java.io.IOException.class,() -> receiver.accept(ComponentCatalogTransfer.chunk(1,bytes,40000)));
        assertThrows(java.io.IOException.class,() -> receiver.accept(new ComponentCatalogTransfer.Chunk(2,ComponentCatalogTransfer.MAX_BYTES+1,0,new byte[]{1})));
        assertThrows(java.io.IOException.class,() -> receiver.accept(new ComponentCatalogTransfer.Chunk(3,1,0,new byte[]{(byte)0xff})));
        receiver.accept(ComponentCatalogTransfer.chunk(4,bytes,0)); time.set(120_000_000_000L); assertTrue(receiver.expire()); assertFalse(receiver.active());
        receiver.clear(); assertEquals("{}",receiver.accept(ComponentCatalogTransfer.chunk(1,ComponentCatalogTransfer.encode("{}"),0)));
    }
    @Test void catalogCodecBoundsChunksAndPreservesRevision() {
        var buffer = new net.minecraft.network.PacketByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            var chunk = new ComponentCatalogTransfer.Chunk(7,2,0,new byte[]{1,2});
            var codec = com.formacraft.common.network.FormaCraftNetworking.ComponentCatalogPayload.CODEC;
            codec.encode(buffer,new com.formacraft.common.network.FormaCraftNetworking.ComponentCatalogPayload(chunk));
            var decoded = codec.decode(buffer).chunk(); assertEquals(7,decoded.revision()); assertArrayEquals(chunk.data(),decoded.data());
            assertThrows(IllegalArgumentException.class,() -> codec.encode(buffer,new com.formacraft.common.network.FormaCraftNetworking.ComponentCatalogPayload(
                    new ComponentCatalogTransfer.Chunk(8,50000,0,new byte[ComponentCatalogTransfer.CHUNK_BYTES+1]))));
        } finally { buffer.release(); }
    }
}
