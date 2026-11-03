package com.formacraft.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommunityPlacementNetworkingTest {
    @Test void optionalSourceReferenceRoundTripsWithoutReceiptOrPathFields() {
        for(var selected:new boolean[]{false,true}) {
            var ref=selected ? new CommunitySelectionRef(UUID.randomUUID(),UUID.randomUUID()) : null;
            var request=new FormaCraftNetworking.RequestPatchPreviewPayload(new BlockPos(1,2,3),"asset_a","SOUTH","NONE",false,null,false,null,null,List.of(),false,ref);
            var buffer=new PacketByteBuf(Unpooled.buffer());
            try {
                FormaCraftNetworking.RequestPatchPreviewPayload.CODEC.encode(buffer,request); assertTrue(buffer.readableBytes()<256);
                var decoded=FormaCraftNetworking.RequestPatchPreviewPayload.CODEC.decode(buffer);
                assertEquals(ref,decoded.communitySource()); assertEquals("asset_a",decoded.componentId()); assertEquals(request.origin(),decoded.origin()); assertFalse(decoded.autoConfirm());
                assertEquals(0,buffer.readableBytes());
            } finally { buffer.release(); }
        }
    }
    @Test void nullIdentityAndTruncatedReferenceAreRejected() {
        assertThrows(NullPointerException.class,()->new CommunitySelectionRef(null,UUID.randomUUID()));
        var buffer=new PacketByteBuf(Unpooled.buffer());
        try {
            var request=new FormaCraftNetworking.RequestPatchPreviewPayload(BlockPos.ORIGIN,"asset_a","SOUTH","NONE",false,null,false,null,null,List.of(),false,new CommunitySelectionRef(UUID.randomUUID(),UUID.randomUUID()));
            FormaCraftNetworking.RequestPatchPreviewPayload.CODEC.encode(buffer,request); buffer.writerIndex(buffer.writerIndex()-1);
            assertThrows(RuntimeException.class,()->FormaCraftNetworking.RequestPatchPreviewPayload.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
}
