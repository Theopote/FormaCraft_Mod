package com.formacraft.server.preview;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GenerationRequestContextTest {

    @AfterEach
    void tearDown() {
        GenerationRequestContext.clear();
    }

    @Test
    void beginRecordsOwnership() {
        UUID player = UUID.randomUUID();
        BlockPos origin = new BlockPos(1, 2, 3);
        GenerationRequestContext.Scope scope =
                GenerationRequestContext.begin(player, "minecraft:overworld", origin);
        assertEquals(player, scope.playerId());
        assertEquals("minecraft:overworld", scope.worldKey());
        assertEquals(origin, scope.origin());
        assertSame(scope, GenerationRequestContext.current());
        assertEquals(scope.requestId(), GenerationRequestContext.currentRequestId());
    }

    @Test
    void clearRemovesThreadLocal() {
        GenerationRequestContext.begin(UUID.randomUUID(), "schematic", BlockPos.ORIGIN);
        GenerationRequestContext.clear();
        assertNull(GenerationRequestContext.current());
    }

    @Test
    void nestedBeginDoesNotLeakAfterClear() {
        GenerationRequestContext.begin(UUID.randomUUID(), "a", new BlockPos(0, 0, 0));
        // Second begin overwrites (same thread) — callers must clear in finally.
        GenerationRequestContext.begin(UUID.randomUUID(), "b", new BlockPos(1, 1, 1));
        GenerationRequestContext.clear();
        assertNull(GenerationRequestContext.current());
    }

    @Test
    void asyncTaskDoesNotSeeParentThreadScope() throws Exception {
        GenerationRequestContext.begin(UUID.randomUUID(), "main", BlockPos.ORIGIN);
        AtomicReference<GenerationRequestContext.Scope> other = new AtomicReference<>();
        Thread t = new Thread(() -> other.set(GenerationRequestContext.current()));
        t.start();
        t.join();
        assertNull(other.get(), "ThreadLocal must not leak across threads");
        assertNotNull(GenerationRequestContext.current());
    }
}
