package com.formacraft.common.model.request;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FormaRequestCompactorTest {

    @Test void escapedPromptFitsActualSerializedBudgetAndRoundTrips() {
        FormaRequest req = new FormaRequest();
        req.setUserMessage("生成福建土楼三栋");
        req.setRequestText("\\\"\\n".repeat(30_000)+"\nUSER REQUEST:\n生成福建土楼三栋");
        String original=req.getRequestText();
        var buf=new net.minecraft.network.PacketByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            com.formacraft.common.network.packet.RequestBuildPacket.write(buf,req);
            FormaRequest decoded=com.formacraft.common.network.packet.RequestBuildPacket.read(buf);
            org.junit.jupiter.api.Assertions.assertEquals(req.getUserMessage(),decoded.getUserMessage());
            assertTrue(FormaRequestCompactor.jsonLength(decoded)<=FormaRequestCompactor.MAX_PACKET_STRING);
            org.junit.jupiter.api.Assertions.assertEquals(original,req.getRequestText());
        } finally { buf.release(); }
    }

    @Test void oversizedEssentialFieldsFailBeforeNetworkEncodingWithoutDroppingThem() {
        FormaRequest req=new FormaRequest();
        req.setRequestText("short");
        req.setUserMessage("中".repeat(88_341));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->FormaRequestCompactor.compactForNetwork(req));
        org.junit.jupiter.api.Assertions.assertEquals(88_341,req.getUserMessage().length());
    }

    @Test
    void compact_largeRequestText_fitsPacketLimit() {
        FormaRequest req = new FormaRequest();
        req.setUserMessage("盖一座小房子");
        req.setRequestText("X".repeat(35_000) + "\nUSER REQUEST:\n盖一座小房子");
        req.setChatHistory(List.of("AI: " + "Y".repeat(5000)));

        FormaRequest compact = FormaRequestCompactor.compactForNetwork(req);
        int len = FormaRequestCompactor.jsonLength(compact);
        assertTrue(len <= FormaRequestCompactor.MAX_PACKET_STRING, "json length=" + len);
        assertTrue(compact.getRequestText().contains("USER REQUEST:"));
        assertTrue(compact.getRequestText().contains("盖一座小房子"));
    }

    @Test
    void compact_dropsInlineBase64Reference() {
        FormaRequest req = new FormaRequest();
        req.setUserMessage("test");
        req.setRequestText("short");
        List<ReferenceInput> refs = new ArrayList<>();
        refs.add(new ReferenceInput("image_base64", "data:image/png;base64," + "A".repeat(10_000), "img"));
        refs.add(new ReferenceInput("image_url", "https://example.com/a.jpg", null));
        req.setReferences(refs);

        FormaRequest compact = FormaRequestCompactor.compactForNetwork(req);
        assertTrue(compact.getReferences().size() == 1);
        assertTrue("image_url".equals(compact.getReferences().get(0).getType()));
    }

    @Test
    void preserveUserRequestSection_keepsTail() {
        String text = "SYS".repeat(1000) + "\nUSER REQUEST:\nbuild cottage";
        String out = FormaRequestCompactor.preserveUserRequestSection(text, 200);
        assertTrue(out.contains("USER REQUEST:"));
        assertTrue(out.contains("build cottage"));
    }
}
