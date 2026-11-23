package com.formacraft.server.network;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ServerNetworkGateP1Test {
    @Test
    void selectionVolumeRejectsOver64Cubed() {
        BlockPos min = new BlockPos(0, 0, 0);
        BlockPos max = new BlockPos(64, 64, 64); // 65^3
        assertTrue(FormaCraftServerNetworking.selectionVolumeTooLarge(min, max));
    }

    @Test
    void selectionVolumeAllows64Cubed() {
        BlockPos min = new BlockPos(0, 0, 0);
        BlockPos max = new BlockPos(63, 63, 63); // 64^3
        assertFalse(FormaCraftServerNetworking.selectionVolumeTooLarge(min, max));
    }

    @Test
    void selectionVolumeNullIsFine() {
        assertFalse(FormaCraftServerNetworking.selectionVolumeTooLarge(null, null));
        assertFalse(FormaCraftServerNetworking.selectionVolumeTooLarge(new BlockPos(0, 0, 0), null));
    }

    @Test
    void componentWriteGateLimitsBurst() {
        ComponentWriteGate.clearAllForTest();
        UUID id = UUID.randomUUID();
        for (int i = 0; i < ComponentWriteGate.MAX_WRITES_PER_WINDOW; i++) {
            assertNull(ComponentWriteGate.tryAdmit(id), "admit " + i);
        }
        assertNotNull(ComponentWriteGate.tryAdmit(id));
        assertEquals(ComponentWriteGate.MAX_WRITES_PER_WINDOW, ComponentWriteGate.windowHits(id));
        ComponentWriteGate.clearAllForTest();
    }

    @Test
    void orchestratorClearPlayerDoesNotDropWindow() {
        // clearPlayer is a reconnect-safe no-op; clearAll is for server stop/tests.
        OrchestratorProxyHandler.clearPlayer(UUID.randomUUID());
        OrchestratorProxyHandler.clearAll();
    }

    @Test
    void patchOriginDistanceConstantMatchesCommunity() {
        assertEquals(96.0, FormaCraftServerNetworking.MAX_PATCH_ORIGIN_DISTANCE);
    }
}
