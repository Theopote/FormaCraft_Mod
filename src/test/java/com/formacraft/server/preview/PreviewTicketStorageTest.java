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
}
