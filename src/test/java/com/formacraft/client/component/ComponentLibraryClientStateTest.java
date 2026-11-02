package com.formacraft.client.component;

import com.formacraft.common.network.ComponentLibraryNetworking.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentLibraryClientStateTest {
    @Test void contiguousDownloadIgnoresStaleRepliesAndReturnsIsolatedBytes() {
        var state = new ComponentLibraryClientState(System::nanoTime); var start = state.begin("asset_a","ref",0,"ref",false);
        assertNull(state.accept(new Response(UUID.randomUUID(),Reply.CHUNK,4,0,new byte[]{1,2},"")));
        var next = state.accept(new Response(start.token(),Reply.CHUNK,4,0,new byte[]{1,2},"")); assertEquals(2,next.offset());
        assertThrows(IllegalStateException.class,() -> state.begin("asset_b","ref",0,"ref",true));
        state.accept(new Response(start.token(),Reply.CHUNK,4,2,new byte[]{3,4},""));
        assertEquals(ComponentLibraryClientState.Phase.READY,state.phase()); assertArrayEquals(new byte[]{1,2,3,4},state.bytes());
        byte[] data = state.bytes(); data[0] = 0; assertEquals(1,state.bytes()[0]);
    }
    @Test void rejectsInconsistentSizesAndDuplicateOffsets() {
        var state = new ComponentLibraryClientState(System::nanoTime); var start = state.begin("asset_a","ref",0,"ref",false);
        state.accept(new Response(start.token(),Reply.CHUNK,4,0,new byte[]{1,2},""));
        state.accept(new Response(start.token(),Reply.CHUNK,5,2,new byte[]{3},"")); assertEquals(ComponentLibraryClientState.Phase.ERROR,state.phase());
        start = state.begin("asset_a","ref",0,"ref",false);
        state.accept(new Response(start.token(),Reply.CHUNK,4,1,new byte[]{1},"")); assertNull(state.bytes());
    }
    @Test void deleteRequiresMatchingAckAndTimeoutDoesNotPretendSuccess() {
        var clock = new java.util.concurrent.atomic.AtomicLong(); var state = new ComponentLibraryClientState(clock::get);
        var start = state.begin("asset_a","ref",0,"ref",true); clock.set(120_000_000_000L); assertTrue(state.expire());
        state.accept(new Response(start.token(),Reply.DELETED,0,0,null,"")); assertEquals(ComponentLibraryClientState.Phase.ERROR,state.phase());
        start = state.begin("asset_a","ref",0,"ref",true); state.accept(new Response(start.token(),Reply.DELETED,0,0,null,""));
        assertEquals(ComponentLibraryClientState.Phase.DELETED,state.phase()); state.clear(); assertNull(state.cancellation());
    }
}
