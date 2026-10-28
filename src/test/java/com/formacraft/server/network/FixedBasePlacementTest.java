package com.formacraft.server.network;

import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.model.request.FormaRequestCompactor;
import com.formacraft.common.json.JsonUtil;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FixedBasePlacementTest {
    @Test void explicitWorkPlaneWinsOverAChangedModelAnchorAndClampsToWorld() {
        var req = new FormaRequest(); req.setFixedBaseY(120); req.setPromptMode("BUILD");
        assertTrue(FixedBasePlacement.enabled(req));
        assertEquals(new BlockPos(10, 120, 30), FixedBasePlacement.origin(req, new BlockPos(10, 65, 30), -64, 319));
        req.setFixedBaseY(999);
        assertEquals(319, FixedBasePlacement.origin(req, BlockPos.ORIGIN, -64, 319).getY());
    }
    @Test void editRequestsAndOrdinaryTerrainFollowingKeepOriginalPlacement() {
        var req = new FormaRequest(); var anchor = new BlockPos(10, 65, 30);
        assertFalse(FixedBasePlacement.enabled(req));
        req.setFixedBaseY(120);
        for (String mode : new String[]{"PATCH", "MODIFY_REGION"}) {
            req.setPromptMode(mode);
            assertFalse(FixedBasePlacement.enabled(req));
            assertSame(anchor, FixedBasePlacement.origin(req, anchor, -64, 319));
        }
    }
    @Test void networkCompactionAndJsonPreserveTheAuthoritativeHeight() {
        var req = new FormaRequest(); req.setFixedBaseY(120); req.setPromptMode("BUILD"); req.setRequestText("build a bridge");
        var compact = FormaRequestCompactor.compactForNetwork(req);
        assertEquals(120, compact.getFixedBaseY());
        assertEquals(120, JsonUtil.fromJson(JsonUtil.toJson(compact), FormaRequest.class).getFixedBaseY());
        var buffer = new net.minecraft.network.PacketByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            com.formacraft.common.network.packet.RequestBuildPacket.write(buffer, compact);
            var received = com.formacraft.common.network.packet.RequestBuildPacket.read(buffer);
            assertEquals(120, received.getFixedBaseY());
            assertTrue(FixedBasePlacement.enabled(received));
        } finally {
            buffer.release();
        }
    }
}
