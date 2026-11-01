package com.formacraft.server.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compensation-record persistence: round-trip, corrupt JSON, leftover temps.
 * <p>
 * These tests exercise filesystem replace semantics — not power-loss fsync guarantees.
 */
class MemoryCommitStoreRecoveryTest {

    @TempDir
    Path saveRoot;

    private static MemoryCommitRecord sample(String uuid) {
        MemoryCommitRecord record = new MemoryCommitRecord();
        record.memoryUuid = uuid;
        record.dimension = "minecraft:overworld";
        record.name = "tower";
        record.transactionId = UUID.randomUUID().toString();
        record.positions.add(new int[]{0, 1, 0});
        record.states.add("minecraft:stone");
        return record;
    }

    @Test
    void saveAndLoadRoundTripSurvivesRestartShape() throws Exception {
        String uuid = UUID.randomUUID().toString();
        MemoryCommitRecord record = sample(uuid);
        record.markExecuted(1, "deadbeef", "COMPLETED");
        record.phaseMemoryPersisted = true;

        assertTrue(MemoryCommitStore.saveToRoot(saveRoot, record));

        List<MemoryCommitRecord> loaded = MemoryCommitStore.loadAllFromRoot(saveRoot);
        assertEquals(1, loaded.size());
        MemoryCommitRecord parsed = loaded.getFirst();
        assertEquals(uuid, parsed.memoryUuid);
        assertEquals("tower", parsed.name);
        assertTrue(parsed.isExecuted());
        assertEquals(1, parsed.appliedPositions);
        assertEquals("deadbeef", parsed.executionHash);
        assertTrue(parsed.phaseMemoryPersisted);
        assertFalse(parsed.phaseUndoPersisted);
    }

    @Test
    void nullSaveRootIsNotReportedAsSuccess() throws Exception {
        assertFalse(MemoryCommitStore.saveToRoot(null, sample(UUID.randomUUID().toString())));
    }

    @Test
    void corruptJsonIsSkippedAndDoesNotAbortLoad() throws Exception {
        String goodUuid = UUID.randomUUID().toString();
        assertTrue(MemoryCommitStore.saveToRoot(saveRoot, sample(goodUuid)));

        Path dir = MemoryCommitStore.dirForRoot(saveRoot);
        Files.writeString(dir.resolve("commit_broken.json"), "{not-valid-json", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("commit_empty.json"), "", StandardCharsets.UTF_8);

        List<MemoryCommitRecord> loaded = MemoryCommitStore.loadAllFromRoot(saveRoot);
        assertEquals(1, loaded.size());
        assertEquals(goodUuid, loaded.getFirst().memoryUuid);
    }

    @Test
    void leftoverTempFilesDoNotAppearAsCommitRecords() throws Exception {
        String uuid = UUID.randomUUID().toString();
        assertTrue(MemoryCommitStore.saveToRoot(saveRoot, sample(uuid)));

        Path dir = MemoryCommitStore.dirForRoot(saveRoot);
        Files.writeString(dir.resolve(".commit-leftover.tmp"),
                sample(UUID.randomUUID().toString()).toJson(), StandardCharsets.UTF_8);

        List<MemoryCommitRecord> loaded = MemoryCommitStore.loadAllFromRoot(saveRoot);
        assertEquals(1, loaded.size());
        assertEquals(uuid, loaded.getFirst().memoryUuid);
    }

    @Test
    void overwriteReplacesPreviousRecordAtomicallyFromCallerView() throws Exception {
        String uuid = UUID.randomUUID().toString();
        MemoryCommitRecord first = sample(uuid);
        first.name = "v1";
        assertTrue(MemoryCommitStore.saveToRoot(saveRoot, first));

        MemoryCommitRecord second = sample(uuid);
        second.name = "v2";
        second.markExecuted(3, "abc", "COMPLETED");
        assertTrue(MemoryCommitStore.saveToRoot(saveRoot, second));

        List<MemoryCommitRecord> loaded = MemoryCommitStore.loadAllFromRoot(saveRoot);
        assertEquals(1, loaded.size());
        assertEquals("v2", loaded.getFirst().name);
        assertEquals(3, loaded.getFirst().appliedPositions);
    }
}
