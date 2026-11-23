package com.formacraft.server.memory;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SpatialIndexSafetyTest {
    private static final Identifier OVERWORLD = Identifier.of("minecraft:overworld");

    private ProjectMemory memory(String uuid, int minX, int maxX) {
        var memory = new ProjectMemory();
        memory.setUuid(uuid);
        memory.setBounds(new ProjectMemory.SpatialBounds(
                new BlockPos(minX, 0, 0), new BlockPos(maxX, 8, 4), OVERWORLD));
        return memory;
    }

    @Test
    void removeMemoryDoesNotDropConcurrentAddOnSameChunk() throws Exception {
        var index = new SpatialIndex();
        var first = memory("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 0, 4);
        var second = memory("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 0, 4);
        index.addMemory(first);

        var ready = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread remover = new Thread(() -> {
            try {
                ready.await();
                index.removeMemory(first.getUuid());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        remover.start();
        ready.countDown();
        // Overlap remove with add on the same chunk key.
        index.addMemory(second);
        done.await();
        assertNull(error.get());
        assertSame(second, index.getMemory(second.getUuid()));
        assertEquals(1, index.findAt(OVERWORLD, new BlockPos(1, 1, 1)).size());
        assertSame(second, index.findAt(OVERWORLD, new BlockPos(1, 1, 1)).getFirst());
    }

    @Test
    void findNearestDedupsCrossChunkBuildingsAndCapsDistance() {
        var index = new SpatialIndex();
        // Spans two chunks so the same UUID appears in multiple chunk sets.
        var wide = memory("cccccccc-cccc-cccc-cccc-cccccccccccc", 0, 20);
        index.addMemory(wide);
        assertSame(wide, index.findNearest(OVERWORLD, new BlockPos(8, 1, 1), 64));
        // Distance beyond cap should still not explode; far building outside cap is ignored.
        var far = memory("dddddddd-dddd-dddd-dddd-dddddddddddd", 10_000, 10_004);
        index.addMemory(far);
        assertNull(index.findNearest(OVERWORLD, BlockPos.ORIGIN, 10_000));
        assertSame(wide, index.findNearest(OVERWORLD, new BlockPos(8, 1, 1), 10_000));
    }
}
