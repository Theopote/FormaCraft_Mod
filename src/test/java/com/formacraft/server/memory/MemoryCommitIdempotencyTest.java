package com.formacraft.server.memory;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.Blocks;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Memory commit must be idempotent and dimension-bound.
 */
class MemoryCommitIdempotencyTest {

    private static final Identifier OVERWORLD = Identifier.of("minecraft:overworld");
    private static final Identifier NETHER = Identifier.of("minecraft:the_nether");

    /** MemoryManager with an in-memory persistence stub (no world folder needed). */
    private static final class TestMemoryManager extends MemoryManager {
        final List<ProjectMemory> persisted = new ArrayList<>();

        TestMemoryManager() {
            super(null);
        }

        @Override
        protected void persistMemory(ProjectMemory memory) {
            persisted.add(memory);
        }
    }

    private static GeneratedStructure structure(int x) {
        return new GeneratedStructure(UUID.randomUUID(), new BlockPos(x, 0, 0), "tower",
                List.of(new PlannedBlock(new BlockPos(x, 1, 0), Blocks.STONE.getDefaultState())));
    }

    @Test
    void repeatedCommitWithSameUuidKeepsOneBuildingIdentity() {
        var manager = new TestMemoryManager();
        var uuid = UUID.randomUUID().toString();

        var first = manager.commitBuilding(uuid, structure(0), null, "tower", OVERWORLD);
        assertNotNull(first);
        assertEquals(uuid, first.getUuid());

        // A retry must update the same record, never create a second building.
        var second = manager.commitBuilding(uuid, structure(0), null, "tower", OVERWORLD);
        assertNotNull(second);
        assertEquals(uuid, second.getUuid());
        assertSame(first, second);

        // Only one indexed building exists for this identity.
        assertEquals(1, manager.getStats().getSpatialCount());
        assertEquals(1, manager.getStats().getSemanticCount());
    }

    @Test
    void commitKeepsTheOwningDimension() {
        var manager = new TestMemoryManager();
        var uuid = UUID.randomUUID().toString();
        var memory = manager.commitBuilding(uuid, structure(0), null, "tower", NETHER);
        assertNotNull(memory);
        assertNotNull(memory.getBounds());
        assertEquals(NETHER.toString(), memory.getBounds().getDimension());
        // The Nether building must not be found in the overworld.
        assertTrue(manager.findAtPosition(OVERWORLD, new BlockPos(0, 1, 0)).isEmpty());
        assertEquals(1, manager.findAtPosition(NETHER, new BlockPos(0, 1, 0)).size());
    }

    @Test
    void commitWithoutDimensionIsRejected() {
        var manager = new TestMemoryManager();
        assertNull(manager.commitBuilding(UUID.randomUUID().toString(), structure(0), null, "tower", null));
    }
}
