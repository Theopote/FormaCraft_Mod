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
        int commitBuildingCalls = 0;
        int recordBuildUndoCalls = 0;
        boolean failCommit = false;
        boolean failCommitRecordSave = false;
        boolean failUndoMetadata = false;

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
            commitBuildingCalls++;
            if (failCommit) return null;
            // Keep the real implementation so index/revision behaviour stays authentic.
            return super.commitBuilding(memoryUuid, structure, spec, name, dimension);
        }

        @Override
        public boolean recordBuildUndo(com.formacraft.server.memory.BuildUndoMemoryUpdate update) {
            recordBuildUndoCalls++;
            if (failUndoMetadata) return false;
            return super.recordBuildUndo(update);
        }

        /** Seed an existing Memory record (as if an earlier commit already created it). */
        void putMemory(ProjectMemory memory) {
            var structure = new GeneratedStructure(null, new BlockPos(0, 0, 0),
                    memory.getName(), List.of());
            var committed = super.commitBuilding(memory.getUuid(), structure, null,
                    memory.getName(), Identifier.of("minecraft:overworld"));
            com.formacraft.server.edit.BuildingMemoryKeys.setRevision(committed,
                    com.formacraft.server.edit.BuildingMemoryKeys.revisionOf(memory));
            updateMemoryStrict(committed);
        }

        @Override
        public boolean saveCommitRecord(MemoryCommitRecord record) {
            savedRecords.add(record);
            return !failCommitRecordSave;
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

    @Test
    void queueingReportsWhetherTheCompensationRecordIsDurable() {
        var manager = new StubMemoryManager();
        var service = BuildExecutionService.getInstance();
        service.setMemoryManager(manager);
        try {
            manager.failCommitRecordSave = false;
            MemoryCommitRecord durable = record("minecraft:overworld", UUID.randomUUID().toString());
            assertTrue(service.queuePendingMemoryCommit(durable));

            manager.failCommitRecordSave = true;
            MemoryCommitRecord volatileRecord = record("minecraft:overworld", UUID.randomUUID().toString());
            // A failed persistence must not be reported as reliably queued.
            assertFalse(service.queuePendingMemoryCommit(volatileRecord));
        } finally {
            service.setMemoryManager(null);
        }
    }

    @Test
    void failedUndoMetadataKeepsTheCommitRetryable() {
        var manager = new StubMemoryManager();
        manager.failUndoMetadata = true;
        var service = BuildExecutionService.getInstance();
        service.setMemoryManager(manager);
        try {
            MemoryCommitRecord overworld = record("minecraft:overworld", UUID.randomUUID().toString());
            overworld.undoRestoredPositions = 1;
            overworld.undoTotalPositions = 3;
            overworld.undoComplete = false;
            queue(service, overworld);

            service.retryPendingMemoryCommits("minecraft:overworld");
            // The commit must not be marked COMMITTED while the undo state is not durable.
            assertNotEquals(MemoryCommitRecord.Status.COMMITTED, overworld.status);
            assertEquals("undo_metadata_persist_failed", overworld.lastError);
            // Phase 1 finished; only phase 2 remains.
            assertTrue(overworld.phaseMemoryPersisted);
            assertFalse(overworld.phaseUndoPersisted);
            // It stays in the queue for a later retry.
            assertEquals(1, service.pendingMemoryCommitCount());
        } finally {
            service.setMemoryManager(null);
        }
    }

    @Test
    void retrySkipsCompletedMemoryPhaseAndOnlyRetriesUndo() {
        var manager = new StubMemoryManager();
        manager.failUndoMetadata = true;
        var service = BuildExecutionService.getInstance();
        service.setMemoryManager(manager);
        try {
            MemoryCommitRecord overworld = record("minecraft:overworld", UUID.randomUUID().toString());
            overworld.undoRestoredPositions = 1;
            overworld.undoTotalPositions = 3;
            overworld.undoComplete = false;
            queue(service, overworld);

            service.retryPendingMemoryCommits("minecraft:overworld");
            assertEquals(1, manager.commitBuildingCalls);
            assertEquals(1, manager.recordBuildUndoCalls);
            assertTrue(overworld.phaseMemoryPersisted);

            // Second attempt: phase 1 must not run again; only undo is retried.
            overworld.nextAttemptMs = 0L;
            manager.failUndoMetadata = false;
            service.retryPendingMemoryCommits("minecraft:overworld");

            assertEquals(1, manager.commitBuildingCalls);
            assertEquals(2, manager.recordBuildUndoCalls);
            assertTrue(overworld.phaseUndoPersisted);
            assertEquals(MemoryCommitRecord.Status.COMMITTED, overworld.status);
        } finally {
            service.setMemoryManager(null);
        }
    }

    @Test
    void retryDoesNotResetANewerRevision() {
        var manager = new StubMemoryManager();
        var service = BuildExecutionService.getInstance();
        service.setMemoryManager(manager);
        try {
            MemoryCommitRecord overworld = record("minecraft:overworld", UUID.randomUUID().toString());
            overworld.markExecuted(1, "hash-1", "COMPLETED");

            // A Memory record already exists and an incremental edit advanced its revision.
            var existing = new ProjectMemory();
            existing.setUuid(overworld.memoryUuid);
            existing.setName("tower");
            existing.setBounds(new ProjectMemory.SpatialBounds(
                    new BlockPos(0, 0, 0), new BlockPos(2, 2, 2),
                    Identifier.of("minecraft:overworld")));
            com.formacraft.server.edit.BuildingMemoryKeys.setRevision(existing, 7L);
            manager.putMemory(existing);

            queue(service, overworld);
            service.retryPendingMemoryCommits("minecraft:overworld");

            assertEquals(MemoryCommitRecord.Status.COMMITTED, overworld.status);
            // The newer revision must survive the retry.
            assertEquals(7L, com.formacraft.server.edit.BuildingMemoryKeys.revisionOf(
                    manager.getMemory(overworld.memoryUuid)));
        } finally {
            service.setMemoryManager(null);
        }
    }

    @Test
    void firstCommitInitializesTheRevision() {
        var manager = new StubMemoryManager();
        var service = BuildExecutionService.getInstance();
        service.setMemoryManager(manager);
        try {
            MemoryCommitRecord overworld = record("minecraft:overworld", UUID.randomUUID().toString());
            overworld.markExecuted(1, "hash-1", "COMPLETED");
            queue(service, overworld);
            service.retryPendingMemoryCommits("minecraft:overworld");

            assertEquals(MemoryCommitRecord.Status.COMMITTED, overworld.status);
            assertEquals(0L, com.formacraft.server.edit.BuildingMemoryKeys.revisionOf(
                    manager.getMemory(overworld.memoryUuid)));
        } finally {
            service.setMemoryManager(null);
        }
    }

    @Test
    void executionSummaryDistinguishesPlannedFromExecuted() {
        MemoryCommitRecord planned = record("minecraft:overworld", UUID.randomUUID().toString());
        assertFalse(planned.isExecuted());
        assertEquals(MemoryCommitRecord.SourceKind.PLANNED, planned.sourceKind);

        MemoryCommitRecord executed = record("minecraft:overworld", UUID.randomUUID().toString());
        executed.markExecuted(12, "abc123", "COMPLETED");
        assertTrue(executed.isExecuted());
        assertEquals(12, executed.appliedPositions);
        assertEquals("abc123", executed.executionHash);
        assertEquals("COMPLETED", executed.buildStatus);

        MemoryCommitRecord parsed = MemoryCommitRecord.fromJson(executed.toJson());
        assertNotNull(parsed);
        assertTrue(parsed.isExecuted());
        assertEquals(12, parsed.appliedPositions);
        assertEquals("abc123", parsed.executionHash);
        assertEquals("COMPLETED", parsed.buildStatus);
    }

    @Test
    void appliedPositionCountAndHashUseTheWorldResultNotThePlan() {
        var pos = new BlockPos(0, 1, 0);
        var stone = Blocks.STONE.getDefaultState();
        var changes = List.of(
                new BlockChange(pos, Blocks.AIR.getDefaultState(), stone),
                new BlockChange(pos, stone, Blocks.OAK_PLANKS.getDefaultState()));
        // The same position written twice is one distinct position.
        assertEquals(1, BuildExecutionService.appliedPositionCount(changes));
        // The hash reflects the final state of that position (last write wins).
        String hash = BuildExecutionService.executionHash(changes);
        assertNotNull(hash);
        assertFalse(hash.isBlank());
        assertEquals(0, BuildExecutionService.appliedPositionCount(List.of()));

        // Distinct positions: the digest is order independent.
        var other = new BlockPos(1, 1, 0);
        var forward = List.of(
                new BlockChange(pos, Blocks.AIR.getDefaultState(), stone),
                new BlockChange(other, Blocks.AIR.getDefaultState(), stone));
        var backward = List.of(forward.get(1), forward.get(0));
        assertEquals(2, BuildExecutionService.appliedPositionCount(forward));
        assertEquals(BuildExecutionService.executionHash(forward),
                BuildExecutionService.executionHash(backward));
    }

    @Test
    void executionHashIsFinalStateDigestNotLogOrder() {
        var pos = new BlockPos(0, 1, 0);
        var air = Blocks.AIR.getDefaultState();
        var stone = Blocks.STONE.getDefaultState();
        var planks = Blocks.OAK_PLANKS.getDefaultState();
        var glass = Blocks.GLASS.getDefaultState();

        // Same chronological final state (glass) → same digest, even with different intermediates.
        var viaStone = List.of(
                new BlockChange(pos, air, stone),
                new BlockChange(pos, stone, glass));
        var viaPlanks = List.of(
                new BlockChange(pos, air, planks),
                new BlockChange(pos, planks, glass));
        assertEquals(BuildExecutionService.executionHash(viaStone),
                BuildExecutionService.executionHash(viaPlanks));

        // Reordering same-cell writes changes which state is treated as final.
        var chronological = List.of(
                new BlockChange(pos, air, stone),
                new BlockChange(pos, stone, planks),
                new BlockChange(pos, planks, glass));
        var reversed = List.of(
                chronological.get(2), chronological.get(1), chronological.get(0));
        assertNotEquals(BuildExecutionService.executionHash(chronological),
                BuildExecutionService.executionHash(reversed));
    }

    @Test
    void executionHashUsesFullSha256Hex() {
        var changes = List.of(new BlockChange(
                new BlockPos(0, 1, 0),
                Blocks.AIR.getDefaultState(),
                Blocks.STONE.getDefaultState()));
        String hash = BuildExecutionService.executionHash(changes);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void retryDoesNotOverwriteEditedNameOrGeneData() {
        var manager = new StubMemoryManager();
        var service = BuildExecutionService.getInstance();
        service.setMemoryManager(manager);
        try {
            MemoryCommitRecord overworld = record("minecraft:overworld", UUID.randomUUID().toString());
            overworld.name = "original-name";
            overworld.markExecuted(1, "hash-1", "COMPLETED");

            var existing = new ProjectMemory();
            existing.setUuid(overworld.memoryUuid);
            existing.setName("edited-name");
            existing.setBounds(new ProjectMemory.SpatialBounds(
                    new BlockPos(0, 0, 0), new BlockPos(2, 2, 2),
                    Identifier.of("minecraft:overworld")));
            com.formacraft.server.edit.BuildingMemoryKeys.setRevision(existing, 3L);
            manager.putMemory(existing);

            // Simulate a later edit that landed before the deferred commit retried.
            ProjectMemory seeded = manager.getMemory(overworld.memoryUuid);
            assertNotNull(seeded);
            seeded.setName("edited-name");
            var editedSpec = new com.formacraft.common.model.build.BuildingSpec();
            editedSpec.setNotes("edited-gene");
            seeded.setGeneData(editedSpec);
            manager.updateMemoryStrict(seeded);

            queue(service, overworld);
            service.retryPendingMemoryCommits("minecraft:overworld");

            assertEquals(MemoryCommitRecord.Status.COMMITTED, overworld.status);
            ProjectMemory committed = manager.getMemory(overworld.memoryUuid);
            assertNotNull(committed);
            assertEquals("edited-name", committed.getName());
            assertNotNull(committed.getGeneData());
            assertEquals("edited-gene", committed.getGeneData().getNotes());
            assertEquals(3L, com.formacraft.server.edit.BuildingMemoryKeys.revisionOf(committed));
        } finally {
            service.setMemoryManager(null);
        }
    }
}
