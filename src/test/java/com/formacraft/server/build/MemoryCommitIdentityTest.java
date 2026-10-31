package com.formacraft.server.build;

import com.formacraft.server.memory.MemoryCommitRecord;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Memory commit identity / dimension / undo-coordination invariants.
 */
class MemoryCommitIdentityTest {

    @Test
    void stableMemoryUuidIsDeterministicPerTransaction() {
        UUID tx = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        String a = BuildExecutionService.stableMemoryUuid(tx, owner, "minecraft:overworld");
        String b = BuildExecutionService.stableMemoryUuid(tx, owner, "minecraft:overworld");
        assertEquals(a, b);
        // Different transaction → different building identity.
        String other = BuildExecutionService.stableMemoryUuid(UUID.randomUUID(), owner, "minecraft:overworld");
        assertNotEquals(a, other);
        // Same identity across dimensions for the same transaction is intentional:
        // a transaction lives in exactly one world, but the id must stay stable.
        assertEquals(a, BuildExecutionService.stableMemoryUuid(tx, owner, "minecraft:the_nether"));
    }

    @Test
    void commitRecordOnlyMatchesItsOwnDimension() {
        MemoryCommitRecord record = new MemoryCommitRecord();
        record.dimension = "minecraft:overworld";
        assertTrue(BuildExecutionService.matchesDimension(record, "minecraft:overworld"));
        assertFalse(BuildExecutionService.matchesDimension(record, "minecraft:the_nether"));
        assertFalse(BuildExecutionService.matchesDimension(record, "minecraft:the_end"));
        // A record without a dimension must never be retried by an arbitrary world.
        MemoryCommitRecord unbound = new MemoryCommitRecord();
        assertFalse(BuildExecutionService.matchesDimension(unbound, "minecraft:overworld"));
    }

    @Test
    void fullyUndoneCommitIsRecognized() {
        MemoryCommitRecord record = new MemoryCommitRecord();
        assertFalse(record.fullyUndone());
        record.undoComplete = true;
        assertTrue(record.fullyUndone());
    }

    @Test
    void commitRecordRoundTripsThroughJson() {
        MemoryCommitRecord record = new MemoryCommitRecord();
        record.memoryUuid = UUID.randomUUID().toString();
        record.transactionId = UUID.randomUUID().toString();
        record.dimension = "minecraft:the_nether";
        record.name = "castle";
        record.status = MemoryCommitRecord.Status.FAILED_NEEDS_ATTENTION;
        record.attempts = 5;
        record.lastError = "boom";
        record.undoRestoredPositions = 3;
        record.undoTotalPositions = 10;

        MemoryCommitRecord parsed = MemoryCommitRecord.fromJson(record.toJson());
        assertNotNull(parsed);
        assertEquals(record.memoryUuid, parsed.memoryUuid);
        assertEquals(record.dimension, parsed.dimension);
        assertEquals(record.status, parsed.status);
        assertEquals(record.attempts, parsed.attempts);
        assertEquals(record.lastError, parsed.lastError);
        assertEquals(record.undoRestoredPositions, parsed.undoRestoredPositions);
        assertEquals(record.undoTotalPositions, parsed.undoTotalPositions);
    }
}
