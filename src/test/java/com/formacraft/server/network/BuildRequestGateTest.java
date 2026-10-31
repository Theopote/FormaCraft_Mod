package com.formacraft.server.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class BuildRequestGateTest {

    @Test
    void releaseTicketIsIdempotentAndPrunesIdleEntry() {
        UUID id = UUID.randomUUID();
        BuildRequestGate.Ticket ticket = BuildRequestGate.forceAdmitForTest(id);
        assertEquals(1, BuildRequestGate.inFlight(id));
        BuildRequestGate.release(ticket);
        assertEquals(0, BuildRequestGate.inFlight(id));
        BuildRequestGate.release(ticket);
        BuildRequestGate.release(ticket);
        assertEquals(0, BuildRequestGate.inFlight(id));
    }

    @Test
    void releaseByUuidDecrementsOneLease() {
        UUID id = UUID.randomUUID();
        BuildRequestGate.forceAdmitForTest(id);
        assertEquals(1, BuildRequestGate.inFlight(id));
        BuildRequestGate.release(id);
        assertEquals(0, BuildRequestGate.inFlight(id));
        BuildRequestGate.release(id);
        assertEquals(0, BuildRequestGate.inFlight(id));
    }

    @Test
    void sweepStaleExpiresAbandonedLeases() {
        UUID id = UUID.randomUUID();
        long past = System.currentTimeMillis() - 1_000L;
        BuildRequestGate.forceAdmitForTest(id, past);
        assertEquals(0, BuildRequestGate.inFlight(id), "expired lease must not count as in-flight");
        BuildRequestGate.sweepStale(System.currentTimeMillis());
        assertEquals(0, BuildRequestGate.inFlight(id));
    }

    @Test
    void releaseAllClearsPlayerOnDisconnect() {
        UUID id = UUID.randomUUID();
        BuildRequestGate.forceAdmitForTest(id);
        BuildRequestGate.forceAdmitForTest(id);
        assertEquals(2, BuildRequestGate.inFlight(id));
        BuildRequestGate.releaseAll(id);
        assertEquals(0, BuildRequestGate.inFlight(id));
        BuildRequestGate.releaseAll(id);
        assertEquals(0, BuildRequestGate.inFlight(id));
    }

    @Test
    void newerAdmitSupersedesOlderPreviewGeneration() {
        UUID id = UUID.randomUUID();
        BuildRequestGate.Ticket older = BuildRequestGate.forceAdmitForTest(id);
        assertTrue(older.mayDeliverPreview());
        BuildRequestGate.Ticket newer = BuildRequestGate.forceAdmitForTest(id);
        assertFalse(older.mayDeliverPreview(), "older ticket must not deliver after a newer admit");
        assertTrue(newer.mayDeliverPreview());
        older.cancel();
        assertFalse(older.mayDeliverPreview());
        assertTrue(newer.mayDeliverPreview());
        BuildRequestGate.releaseAll(id);
    }

    @Test
    void cancelAllInFlightBlocksPreviewDelivery() {
        UUID id = UUID.randomUUID();
        BuildRequestGate.Ticket ticket = BuildRequestGate.forceAdmitForTest(id);
        assertTrue(ticket.mayDeliverPreview());
        assertEquals(1, BuildRequestGate.cancelAllInFlight(id));
        assertTrue(ticket.isCancelled());
        assertFalse(ticket.mayDeliverPreview());
        BuildRequestGate.releaseAll(id);
    }

    @Test
    void requestStateAdvancesButStopsAtTerminalStates() {
        UUID id = UUID.randomUUID();
        BuildRequestGate.Ticket ticket = BuildRequestGate.forceAdmitForTest(id);
        assertEquals(BuildRequestGate.RequestState.ADMITTED, ticket.state());
        ticket.advance(BuildRequestGate.RequestState.PLANNING);
        assertEquals(BuildRequestGate.RequestState.PLANNING, ticket.state());
        ticket.advance(BuildRequestGate.RequestState.COMPILING);
        assertEquals(BuildRequestGate.RequestState.COMPILING, ticket.state());
        ticket.advance(BuildRequestGate.RequestState.FAILED);
        assertEquals(BuildRequestGate.RequestState.FAILED, ticket.state());
        ticket.advance(BuildRequestGate.RequestState.PREVIEW_READY);
        assertEquals(BuildRequestGate.RequestState.FAILED, ticket.state(), "terminal state must not regress");
        BuildRequestGate.releaseAll(id);
    }

    @Test
    void onceFinishStyleCallbackStillReleasesWhenWorkThrows() {
        // Mirrors scheduleBuildPreview deliverPreview + BuildRequestProcessor finish finally.
        UUID id = UUID.randomUUID();
        BuildRequestGate.Ticket ticket = BuildRequestGate.forceAdmitForTest(id);
        AtomicInteger finishCalls = new AtomicInteger();
        AtomicBoolean workThrew = new AtomicBoolean(false);

        Consumer<Boolean> rawFinish = handled -> {
            finishCalls.incrementAndGet();
            BuildRequestGate.release(ticket);
        };
        AtomicBoolean called = new AtomicBoolean(false);
        Consumer<Boolean> finish = handled -> {
            if (!called.compareAndSet(false, true)) return;
            rawFinish.accept(handled);
        };

        boolean handled = false;
        try {
            throw new RuntimeException("simulated tryBuildPreview failure");
        } catch (RuntimeException e) {
            workThrew.set(true);
            handled = true;
        } finally {
            finish.accept(handled);
        }

        assertTrue(workThrew.get());
        assertEquals(1, finishCalls.get());
        assertEquals(0, BuildRequestGate.inFlight(id));
        finish.accept(false); // second call must not double-free / recreate
        assertEquals(1, finishCalls.get());
        assertEquals(0, BuildRequestGate.inFlight(id));
    }
}
