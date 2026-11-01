package com.formacraft.server.memory;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.build.BuildingSpec;
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

    @Test
    void retryDoesNotOverwriteEditedNameOrGeneData() {
        var manager = new TestMemoryManager();
        var uuid = UUID.randomUUID().toString();

        BuildingSpec original = new BuildingSpec();
        original.setNotes("original-build");
        var first = manager.commitBuilding(uuid, structure(0), original, "tower", OVERWORLD);
        assertNotNull(first);

        // Later edit replaces semantic fields before a deferred commit retries.
        BuildingSpec edited = new BuildingSpec();
        edited.setNotes("after-edit");
        first.setName("renamed-tower");
        first.setGeneData(edited);

        BuildingSpec staleRetrySpec = new BuildingSpec();
        staleRetrySpec.setNotes("original-build");
        var retry = manager.commitBuilding(uuid, structure(0), staleRetrySpec, "tower", OVERWORLD);
        assertNotNull(retry);
        assertSame(first, retry);
        assertEquals("renamed-tower", retry.getName());
        assertNotNull(retry.getGeneData());
        assertEquals("after-edit", retry.getGeneData().getNotes());
    }

    @Test
    void retryBackfillsMissingNameAndGeneDataOnly() {
        var manager = new TestMemoryManager();
        var uuid = UUID.randomUUID().toString();

        var first = manager.commitBuilding(uuid, structure(0), null, null, OVERWORLD);
        assertNotNull(first);
        first.setName(null);
        first.setGeneData(null);

        BuildingSpec backfill = new BuildingSpec();
        backfill.setNotes("backfill");
        var retry = manager.commitBuilding(uuid, structure(0), backfill, "tower", OVERWORLD);
        assertNotNull(retry);
        assertEquals("tower", retry.getName());
        assertNotNull(retry.getGeneData());
        assertEquals("backfill", retry.getGeneData().getNotes());
    }

    @Test
    void saveCommitRecordPropagatesNullServerFailure() {
        var manager = new TestMemoryManager(); // server == null
        MemoryCommitRecord record = new MemoryCommitRecord();
        record.memoryUuid = UUID.randomUUID().toString();
        record.dimension = "minecraft:overworld";
        record.name = "tower";
        // Underlying MemoryCommitStore.save returns false when server is null —
        // the manager must not report that as success.
        assertFalse(manager.saveCommitRecord(record));
    }
}
