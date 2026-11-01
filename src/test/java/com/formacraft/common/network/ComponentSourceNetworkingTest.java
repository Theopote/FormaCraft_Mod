package com.formacraft.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ComponentSourceNetworkingTest {
    @Test void requestAndResponsePreserveCorrelationAndPage() {
        var buffer = new PacketByteBuf(Unpooled.buffer());
        try {
            var request = new ComponentSourceNetworking.Request(UUID.randomUUID(), "asset_test", 128);
            ComponentSourceNetworking.Request.CODEC.encode(buffer, request);
            assertEquals(request, ComponentSourceNetworking.Request.CODEC.decode(buffer));
            buffer.clear();
            var response = new ComponentSourceNetworking.Response(request.token(), "{\"name\":\"窗\"}");
            ComponentSourceNetworking.Response.CODEC.encode(buffer, response);
            assertEquals(response, ComponentSourceNetworking.Response.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
    @Test void oversizedSourceMessagesAreRejectedOnEncodingAndDecoding() {
        var buffer = new PacketByteBuf(Unpooled.buffer());
        try {
            var response = new ComponentSourceNetworking.Response(UUID.randomUUID(), "x".repeat(ComponentSourceNetworking.MAX_JSON + 1));
            assertThrows(RuntimeException.class, () -> ComponentSourceNetworking.Response.CODEC.encode(buffer, response));
            buffer.clear(); buffer.writeUuid(response.token()); buffer.writeString(response.json());
            assertThrows(RuntimeException.class, () -> ComponentSourceNetworking.Response.CODEC.decode(buffer));
            buffer.clear();
            assertThrows(RuntimeException.class, () -> ComponentSourceNetworking.Request.CODEC.encode(buffer,
                    new ComponentSourceNetworking.Request(UUID.randomUUID(), "x".repeat(65), 0)));
        } finally { buffer.release(); }
    }
}
