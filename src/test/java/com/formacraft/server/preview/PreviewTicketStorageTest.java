package com.formacraft.server.preview;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PreviewTicketStorageTest {

    @BeforeEach
    void clear() {
        PreviewTicketStorage.clearAllForTest();
    }

    @Test
    void consumeIsOneShot() {
        UUID player = UUID.randomUUID();
        BlockPos origin = new BlockPos(1, 2, 3);
        List<BlockPatch> patches = List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone"));

        PreviewTicket ticket = PreviewTicketStorage.create(player, origin, patches);
        assertNotNull(ticket);

        PreviewTicket first = PreviewTicketStorage.consume(ticket.id(), player);
        assertNotNull(first);
        assertNull(PreviewTicketStorage.consume(ticket.id(), player));
    }

    @Test
    void crossPlayerConsumeFails() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        PreviewTicket ticket = PreviewTicketStorage.create(
                owner, new BlockPos(0, 64, 0),
                List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:dirt")));
        assertNotNull(ticket);
        assertNull(PreviewTicketStorage.consume(ticket.id(), other));
        assertNotNull(PreviewTicketStorage.consume(ticket.id(), owner));
    }

    @Test
    void getLeavesTicketForRetryAfterGateFailure() {
        UUID player = UUID.randomUUID();
        PreviewTicket ticket = PreviewTicketStorage.create(
                player,
                net.minecraft.util.Identifier.of("minecraft", "overworld"),
                new BlockPos(0, 64, 0),
                List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone")));
        assertNotNull(ticket);
        assertEquals("minecraft:overworld", ticket.dimension().toString());

        // 模拟 confirm 门禁：先 get 校验，失败则不 consume
        PreviewTicket peeked = PreviewTicketStorage.get(ticket.id());
        assertNotNull(peeked);
        assertNotNull(PreviewTicketStorage.get(ticket.id()), "门禁失败后票据应仍可确认");
        assertNotNull(PreviewTicketStorage.consume(ticket.id(), player));
    }

    @Test
    void concurrentConsumeSucceedsExactlyOnce() throws Exception {
        UUID player = UUID.randomUUID();
        PreviewTicket ticket = PreviewTicketStorage.create(
                player, new BlockPos(1, 1, 1),
                List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone")));
        assertNotNull(ticket);

        java.util.concurrent.atomic.AtomicInteger wins = new java.util.concurrent.atomic.AtomicInteger();
        Runnable r = () -> {
            if (PreviewTicketStorage.consume(ticket.id(), player) != null) {
                wins.incrementAndGet();
            }
        };
        Thread t1 = new Thread(r);
        Thread t2 = new Thread(r);
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        assertEquals(1, wins.get());
        assertNull(PreviewTicketStorage.get(ticket.id()));
    }

    @Test
    void consumeForApplyReleasesBoundPlan() {
        UUID player = UUID.randomUUID();
        BlockPos origin = new BlockPos(0, 64, 0);
        List<BlockPatch> patches = List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone"));
        PreviewTicket ticket = PreviewTicketStorage.create(player, origin, patches);
        assertNotNull(ticket);
        // Ordinary ticket: no bound plan — consumption still one-shot.
        PreviewTicketStorage.TicketConsumption c =
                PreviewTicketStorage.consumeForApply(ticket.id(), player);
        assertNotNull(c);
        assertNull(c.boundPlan());
        assertNull(PreviewTicketStorage.consumeForApply(ticket.id(), player));
    }

    @Test
    void onServerTickEventuallyPurgesExpired() throws Exception {
        UUID player = UUID.randomUUID();
        PreviewTicket ticket = PreviewTicketStorage.create(
                player, new BlockPos(0, 1, 0),
                List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone")),
                1_000L);
        assertNotNull(ticket);
        Thread.sleep(1_100L);
        for (int i = 0; i < 120; i++) {
            PreviewTicketStorage.onServerTick();
        }
        assertEquals(0, PreviewTicketStorage.sizeForTest());
    }

    @Test
    void ticketCarriesQualitySnapshot() {
        UUID player = UUID.randomUUID();
        var quality = new com.formacraft.server.build.quality.BuildQualityGate.QualitySnapshot(
                false, true, "has error");
        PreviewTicket ticket = PreviewTicketStorage.create(
                player,
                net.minecraft.util.Identifier.of("minecraft", "overworld"),
                new BlockPos(0, 64, 0),
                List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone")),
                quality);
        assertNotNull(ticket);
        assertNotNull(ticket.quality());
        assertTrue(ticket.quality().hasError());
        assertFalse(ticket.quality().hasFatal());
    }
}
