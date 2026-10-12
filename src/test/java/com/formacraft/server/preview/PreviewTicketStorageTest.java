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
        PreviewTicketStorage.clearPlayer(UUID.randomUUID());
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
}
