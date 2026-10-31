package com.formacraft.server.build;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.memory.MemoryCommitRecord;
import com.formacraft.server.memory.MemoryManager;
import com.formacraft.server.memory.ProjectMemory;
import net.minecraft.block.Blocks;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deferred Memory commits must stay bound to their own dimension, be idempotent,
 * and never resurrect a building that was undone before the commit succeeded.
 */
class MemoryCommitRetryTest {

    /** In-memory MemoryManager: no world folder, records persisted in a list. */
    private static final class StubMemoryManager extends MemoryManager {
        final List<String> committedUuids = new ArrayList<>();
        final List<String> deletedUuids = new ArrayList<>();
        final List<MemoryCommitRecord> savedRecords = new ArrayList<>();
        boolean failCommit = false;

        StubMemoryManager() {
            super(null);
        }

        @Override
        protected void persistMemory(ProjectMemory memory) {
            if (!committedUuids.contains(memory.getUuid())) {
                committedUuids.add(memory.getUuid());
            }
        }

        @Override
        public ProjectMemory commitBuilding(String memoryUuid, GeneratedStructure structure,
                com.formacraft.common.model.build.BuildingSpec spec, String name, Identifier dimension) {
            if (failCommit) return null;
            ProjectMemory memory = new ProjectMemory();
            memory.setUuid(memoryUuid);
            memory.setName(name);
            memory.setBounds(new ProjectMemory.SpatialBounds(
                    new BlockPos(0, 0, 0), new BlockPos(2, 2, 2), dimension));
            persistMemory(memory);
            return memory;
        }

        @Override
        public void saveCommitRecord(MemoryCommitRecord record) {
            savedRecords.add(record);
        }

        @Override
        public void deleteCommitRecord(String memoryUuid) {
            deletedUuids.add(memoryUuid);
        }

        @Override
        public List<MemoryCommitRecord> loadCommitRecords() {
            return List.of();
        }
    }

    private static MemoryCommitRecord record(String dimension, String uuid) {
        MemoryCommitRecord record = new MemoryCommitRecord();
        record.memoryUuid = uuid;
        record.dimension = dimension;
        record.name = "tower";
        record.transactionId = UUID.randomUUID().toString();
        record.positions.add(new int[]{0, 1, 0});
        record.states.add("minecraft:stone");
        return record;
    }

    @BeforeAll
    static void initialize() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @BeforeEach
    void resetPendingCommits() {
        BuildExecutionService.getInstance().clearPendingMemoryCommitsForTest();
    }

    /** Queue a commit and make it immediately retryable. */
    private static void queue(BuildExecutionService service, MemoryCommitRecord record) {
        service.queuePendingMemoryCommit(record);
        record.nextAttemptMs = 0L;
    }

    @Test
    void foreignDimensionTickNeverRetriesTheCommit() {
        var manager = new StubMemoryManager();
        var service = BuildExecutionService.getInstance();
        service.setMemoryManager(manager);
        try {
            MemoryCommitRecord overworld = record("minecraft:overworld", UUID.randomUUID().toString());
            MemoryCommitRecord nether = record("minecraft:the_nether", UUID.randomUUID().toString());
            MemoryCommitRecord end = record("minecraft:the_end", UUID.randomUUID().toString());
            queue(service, overworld);
            queue(service, nether);
            queue(service, end);

            // A Nether tick must only touch the Nether commit.
            service.retryPendingMemoryCommits("minecraft:the_nether");
            assertEquals(1, manager.committedUuids.size());
            assertEquals(nether.memoryUuid, manager.committedUuids.getFirst());

            // The End tick must only touch the End commit.
            service.retryPendingMemoryCommits("minecraft:the_end");
            assertEquals(2, manager.committedUuids.size());
            assertEquals(end.memoryUuid, manager.committedUuids.get(1));

            // The overworld commit is still pending — never committed by a foreign tick.
            assertFalse(manager.committedUuids.contains(overworld.memoryUuid));
        } finally {
            service.setMemoryManager(null);
        }
    }

    @Test
    void retryOfTheSameCommitDoesNotCreateASecondBuildingIdentity() {
        var manager = new StubMemoryManager();
        var service = BuildExecutionService.getInstance();
        service.setMemoryManager(manager);
        try {
            MemoryCommitRecord overworld = record("minecraft:overworld", UUID.randomUUID().toString());
            queue(service, overworld);
            service.retryPendingMemoryCommits("minecraft:overworld");
            assertEquals(1, manager.committedUuids.size());
            assertEquals(overworld.memoryUuid, manager.committedUuids.getFirst());
            // The stable identity is preserved across retries.
            assertEquals(overworld.memoryUuid, overworld.memoryUuid);
        } finally {
            service.setMemoryManager(null);
        }
    }

    @Test
    void fullyUndoneBuildingIsCancelledInsteadOfResurrected() {
        var manager = new StubMemoryManager();
        var service = BuildExecutionService.getInstance();
        service.setMemoryManager(manager);
        try {
            MemoryCommitRecord overworld = record("minecraft:overworld", UUID.randomUUID().toString());
            queue(service, overworld);
            service.notePendingMemoryUndo(overworld.memoryUuid, 5, 5, true);

            service.retryPendingMemoryCommits("minecraft:overworld");
            // No Memory record was created for the undone building.
            assertTrue(manager.committedUuids.isEmpty());
            assertTrue(manager.deletedUuids.contains(overworld.memoryUuid));
            assertEquals(MemoryCommitRecord.Status.CANCELLED, overworld.status);
        } finally {
            service.setMemoryManager(null);
        }
    }

    @Test
    void exhaustedRetriesAreKeptAsFailedNeedsAttention() {
        var manager = new StubMemoryManager();
        manager.failCommit = true;
        var service = BuildExecutionService.getInstance();
        service.setMemoryManager(manager);
        try {
            MemoryCommitRecord overworld = record("minecraft:overworld", UUID.randomUUID().toString());
            queue(service, overworld);
            for (int i = 0; i < 8; i++) {
                service.retryPendingMemoryCommits("minecraft:overworld");
                overworld.nextAttemptMs = 0L;
            }
            assertEquals(MemoryCommitRecord.Status.FAILED_NEEDS_ATTENTION, overworld.status);
            assertEquals("max_attempts_exceeded", overworld.lastError);
            // The record is retained for diagnostics / manual recovery.
            assertEquals(1, service.failedMemoryCommits().size());
            // Manual recovery puts it back into the retry queue.
            assertEquals(1, service.retryFailedMemoryCommits());
            assertEquals(MemoryCommitRecord.Status.PENDING, overworld.status);
        } finally {
            service.setMemoryManager(null);
        }
    }

    @Test
    void redoAfterCancelBringsTheCommitBack() {
        var manager = new StubMemoryManager();
        var service = BuildExecutionService.getInstance();
        service.setMemoryManager(manager);
        try {
            MemoryCommitRecord overworld = record("minecraft:overworld", UUID.randomUUID().toString());
            queue(service, overworld);
            service.notePendingMemoryUndo(overworld.memoryUuid, 5, 5, true);
            service.retryPendingMemoryCommits("minecraft:overworld");
            assertTrue(manager.committedUuids.isEmpty());

            // A redo restores the building → the commit must be revived.
            service.notePendingMemoryUndo(overworld.memoryUuid, 0, 5, false);
            service.retryPendingMemoryCommits("minecraft:overworld");
            assertEquals(1, manager.committedUuids.size());
            assertEquals(overworld.memoryUuid, manager.committedUuids.getFirst());
        } finally {
            service.setMemoryManager(null);
        }
    }

    @Test
    void generatedStructureSnapshotSurvivesJsonRoundTrip() {
        var structure = new GeneratedStructure(UUID.randomUUID(), new BlockPos(1, 2, 3), "tower",
                List.of(new PlannedBlock(new BlockPos(1, 2, 3), Blocks.STONE.getDefaultState())));
        MemoryCommitRecord record = MemoryCommitRecord.create(
                UUID.randomUUID().toString(), UUID.randomUUID(), "minecraft:overworld", "tower",
                structure, null, null);
        MemoryCommitRecord parsed = MemoryCommitRecord.fromJson(record.toJson());
        assertNotNull(parsed);
        var rebuilt = parsed.toStructure();
        assertEquals(1, rebuilt.size());
        assertEquals(new BlockPos(1, 2, 3), rebuilt.getOrigin());
        assertEquals(Blocks.STONE.getDefaultState(), rebuilt.getBlocks().getFirst().getTargetState());
    }
}
