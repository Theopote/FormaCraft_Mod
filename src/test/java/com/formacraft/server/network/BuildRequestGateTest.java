package com.formacraft.server.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BuildRequestGateTest {

    @Test
    void releaseDecrementsAndPrunesIdleEntry() {
        UUID id = UUID.randomUUID();
        BuildRequestGate.forceAdmitForTest(id);
        assertEquals(1, BuildRequestGate.inFlight(id));
        BuildRequestGate.release(id);
        assertEquals(0, BuildRequestGate.inFlight(id));
        // Second release must not go negative / recreate entry.
        BuildRequestGate.release(id);
        assertEquals(0, BuildRequestGate.inFlight(id));
    }

    @Test
    void sweepStaleClearsZeroCounters() {
        UUID id = UUID.randomUUID();
        BuildRequestGate.forceAdmitForTest(id);
        BuildRequestGate.release(id);
        BuildRequestGate.sweepStale(System.currentTimeMillis());
        assertEquals(0, BuildRequestGate.inFlight(id));
    }
}
