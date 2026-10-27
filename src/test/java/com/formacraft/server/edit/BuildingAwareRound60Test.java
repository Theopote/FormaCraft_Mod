package com.formacraft.server.edit;

import com.formacraft.common.patch.BuildExecutionStatus;
import com.formacraft.common.patch.WorldPatchTransaction;
import com.formacraft.server.memory.MemoryManager;
import com.formacraft.server.memory.MemoryUpdateResult;
import com.formacraft.server.memory.ProjectMemory;
import com.formacraft.server.memory.SpatialIndex;
import com.formacraft.server.preview.PreviewTicketStorage;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round 60: txn finalize persistence, recovery coordinator semantics,
 * fail-apply status classification, fault injection.
 */
class BuildingAwareRound60Test {
    private static final Identifier OVERWORLD = Identifier.of("minecraft:overworld");

    @TempDir Path tmp;

    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @BeforeEach
    void reset() {
        BuildingEditPendingRegistry.clearForTest();
        BuildingEditTransactionStore.clearForTest();
        PreviewTicketStorage.clearAllForTest();
        BuildingEditTransactionStore.FAIL_NEXT_PERSIST.set(false);
    }

    private ProjectMemory house(String name, BlockPos min, BlockPos max) {
        var memory = new ProjectMemory();
        memory.setName(name);
        var bounds = new ProjectMemory.SpatialBounds(min, max, OVERWORLD);
        bounds.addAnchor("origin", min);
        memory.setBounds(bounds);
        BuildingMemoryKeys.setRevision(memory, 0L);
        return memory;
    }

    private MemoryManager manager(SpatialIndex index) {
        return manager(index, new AtomicBoolean(false));
    }

    private MemoryManager manager(SpatialIndex index, AtomicBoolean failPersist) {
        return new MemoryManager(null) {
            @Override public List<ProjectMemory> findAtPosition(Identifier dimension, BlockPos pos) {
                return index.findAt(dimension, pos);
            }
            @Override public ProjectMemory findNearest(Identifier dimension, BlockPos pos, double maxDistance) {
                return index.findNearest(dimension, pos, maxDistance);
            }
            @Override public ProjectMemory getMemory(String uuid) {
                return index.getMemory(uuid);
            }
            @Override public void updateMemory(ProjectMemory memory) {
                updateMemoryStrict(memory);
            }
            @Override public MemoryUpdateResult updateMemoryStrict(ProjectMemory memory) {
                if (failPersist.get()) {
                    return MemoryUpdateResult.fail("injected_io_failure");
                }
                index.removeMemory(memory.getUuid());
                index.addMemory(memory);
                return MemoryUpdateResult.succeeded();
            }
            @Override protected void persistMemory(ProjectMemory memory) throws IOException {
                if (failPersist.get()) throw new IOException("injected_io_failure");
            }
        };
    }

    private void attachOakRoof(ProjectMemory memory, BlockPos... cells) {
        Map<BlockPos, String> states = new HashMap<>();
        for (BlockPos cell : cells) states.put(cell, "minecraft:oak_stairs");
        BuildingEditService.attachRoofIndex(memory, BuildingPartIndex.ofRoofCells(List.of(cells), states));
    }

    private BuildingEditPendingRegistry.Pending pending(
            UUID ticketId, UUID playerId, ProjectMemory house, BlockPos roof
    ) {
        return BuildingEditPendingRegistry.Pending.roof(
                ticketId, playerId, UUID.fromString(house.getUuid()), OVERWORLD, 0L,
                java.util.Set.of(roof),
                Map.of(roof, "minecraft:oak_stairs"),
                Map.of(roof, "minecraft:dark_oak_stairs"),
                System.currentTimeMillis() + 60_000);
    }

    @Test
    void preparedPersistFailureBlocksBegin() {
        Path dir = tmp.resolve("prep");
        BuildingEditTransactionStore.bindDirectoryForTest(dir);
        var index = new SpatialIndex();
        var house = house("prep", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        BuildingEditTransactionStore.failNextPersistForTest();
        var begin = service.beginTransaction(pending(UUID.randomUUID(), UUID.randomUUID(), house, roof));
        assertFalse(begin.ok());
        assertTrue(begin.message().contains("persist"));
        assertEquals(0, BuildingEditTransactionStore.sizeForTest());
    }

    @Test
    void memoryCommittedPersistFailureKeepsTxnOpenAndDoesNotUnlock() {
        Path dir = tmp.resolve("finalize");
        BuildingEditTransactionStore.bindDirectoryForTest(dir);
        var index = new SpatialIndex();
        var house = house("fin", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        UUID buildingId = UUID.fromString(house.getUuid());
        var begin = service.beginTransaction(pending(UUID.randomUUID(), UUID.randomUUID(), house, roof));
        assertTrue(begin.ok());
        UUID txnId = begin.transaction().transactionId();
        assertTrue(service.markWorldApplied(txnId));
        assertTrue(service.commitSuccessfulRoofEdit(
                buildingId, OVERWORLD, 0L, Map.of(roof, "minecraft:dark_oak_stairs")));
        assertEquals(1L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));

        BuildingEditTransactionStore.failNextPersistForTest();
        assertFalse(service.markMemoryCommitted(txnId));
        // Closing status must roll back in-memory so the building stays blocked.
        assertEquals(BuildingEditTransaction.Status.WORLD_APPLIED,
                BuildingEditTransactionStore.get(txnId).status());
        assertNotNull(BuildingEditTransactionStore.findOpenByBuilding(buildingId));

        // Idempotent finalize after disk recovers — no second Revision bump.
        var finalized = service.finalizeCommittedTransaction(txnId);
        assertTrue(finalized.ok(), () -> finalized.message());
        assertEquals(BuildingEditTransaction.Status.MEMORY_COMMITTED,
                BuildingEditTransactionStore.get(txnId).status());
        assertEquals(1L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));
        assertNull(BuildingEditTransactionStore.findOpenByBuilding(buildingId));

        // Repeat finalize is a no-op success.
        assertTrue(service.finalizeCommittedTransaction(txnId).ok());
        assertEquals(1L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));
    }

    @Test
    void recoveryWhenWorldMatchesTargetCommitsOnce() {
        Path dir = tmp.resolve("rec-target");
        BuildingEditTransactionStore.bindDirectoryForTest(dir);
        var index = new SpatialIndex();
        var house = house("tgt", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        UUID buildingId = UUID.fromString(house.getUuid());
        var begin = service.beginTransaction(pending(UUID.randomUUID(), UUID.randomUUID(), house, roof));
        service.markWorldApplied(begin.transaction().transactionId());
        service.markRecoveryRequired(begin.transaction().transactionId());
        service.markMemorySyncFailed(buildingId, OVERWORLD, 0L, Map.of(roof, "minecraft:dark_oak_stairs"));

        var first = service.repairMemorySyncWithLiveWorld(buildingId, OVERWORLD,
                Map.of(roof, "minecraft:dark_oak_stairs"));
        assertTrue(first.ok(), () -> first.message());
        assertEquals(1L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));

        // Simulate restart gap: Memory already at base+1, durable txn still WORLD_APPLIED.
        BuildingEditTransactionStore.clearForTest();
        BuildingEditTransactionStore.bindDirectoryForTest(tmp.resolve("rec-target-2"));
        var stale = new BuildingEditTransaction(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), buildingId, OVERWORLD, 0L,
                BuildingEditTransaction.Operation.REPLACE_ROOF_MATERIAL,
                Map.of(roof, "minecraft:oak_stairs"),
                Map.of(roof, "minecraft:dark_oak_stairs"),
                BuildingEditTransaction.Status.WORLD_APPLIED,
                System.currentTimeMillis(), System.currentTimeMillis());
        assertTrue(BuildingEditTransactionStore.putPrepared(stale));
        // putPrepared forces PREPARED on disk via persist of current object — reset status.
        assertTrue(service.markWorldApplied(stale.transactionId()));
        var fin = service.finalizeCommittedTransaction(stale.transactionId());
        assertTrue(fin.ok(), () -> fin.message());
        assertEquals(1L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));
        assertNull(BuildingEditTransactionStore.findOpenByBuilding(buildingId));
    }

    @Test
    void recoveryWhenWorldMatchesExpectedAbortsWithoutRevisionBump() {
        BuildingEditTransactionStore.bindDirectoryForTest(tmp.resolve("rec-exp"));
        var index = new SpatialIndex();
        var house = house("exp", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        UUID buildingId = UUID.fromString(house.getUuid());
        var begin = service.beginTransaction(pending(UUID.randomUUID(), UUID.randomUUID(), house, roof));
        service.markWorldApplied(begin.transaction().transactionId());
        service.markRecoveryRequired(begin.transaction().transactionId());

        var undone = service.repairMemorySyncWithLiveWorld(buildingId, OVERWORLD,
                Map.of(roof, "minecraft:oak_stairs"));
        assertTrue(undone.ok(), () -> undone.message());
        assertEquals(0L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));
        assertEquals(BuildingEditTransaction.Status.ABORTED,
                BuildingEditTransactionStore.get(begin.transaction().transactionId()).status());
    }

    @Test
    void recoveryWhenWorldThirdStateKeepsBlocked() {
        BuildingEditTransactionStore.bindDirectoryForTest(tmp.resolve("rec-third"));
        var index = new SpatialIndex();
        var house = house("3rd", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        UUID buildingId = UUID.fromString(house.getUuid());
        var begin = service.beginTransaction(pending(UUID.randomUUID(), UUID.randomUUID(), house, roof));
        service.markWorldApplied(begin.transaction().transactionId());
        service.markRecoveryRequired(begin.transaction().transactionId());

        var mismatch = service.repairMemorySyncWithLiveWorld(buildingId, OVERWORLD,
                Map.of(roof, "minecraft:stone"));
        assertFalse(mismatch.ok());
        assertTrue(mismatch.message().contains("world_state_mismatch"));
        assertNotNull(BuildingEditTransactionStore.findOpenByBuilding(buildingId));
        assertEquals(0L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));
    }

    @Test
    void abortPersistFailureDoesNotSilentlyClose() {
        BuildingEditTransactionStore.bindDirectoryForTest(tmp.resolve("abort"));
        var index = new SpatialIndex();
        var house = house("ab", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        var begin = service.beginTransaction(pending(UUID.randomUUID(), UUID.randomUUID(), house, roof));
        UUID txnId = begin.transaction().transactionId();
        BuildingEditTransactionStore.failNextPersistForTest();
        assertFalse(service.abortTransaction(txnId));
        assertEquals(BuildingEditTransaction.Status.PREPARED,
                BuildingEditTransactionStore.get(txnId).status());
        assertNotNull(BuildingEditTransactionStore.findOpenByBuilding(UUID.fromString(house.getUuid())));
    }

    @Test
    void worldPatchStatusesDocumentAbortSafety() {
        // Contract used by PatchPreviewService fail-apply branching.
        assertTrue(BuildExecutionStatus.PREFLIGHT_REJECTED.labelZh().contains("拒绝"));
        assertTrue(BuildExecutionStatus.ROLLED_BACK.labelZh().contains("回滚"));
        assertTrue(BuildExecutionStatus.ROLLBACK_INCOMPLETE.labelZh().contains("不完整"));
        // SUCCESS records history; incomplete rollback must not be treated as clean abort.
        assertTrue(BuildExecutionStatus.SUCCESS.recordsHistory());
        assertFalse(BuildExecutionStatus.ROLLBACK_INCOMPLETE.recordsHistory());
        assertFalse(BuildExecutionStatus.PREFLIGHT_REJECTED.recordsHistory());
        // Result factory still constructs without a world.
        var rejected = WorldPatchTransaction.Result.preflightRejected(null);
        assertEquals(BuildExecutionStatus.PREFLIGHT_REJECTED, rejected.status());
    }

    @Test
    void durableWorldAppliedSurvivesRebindForRecoveryScan() {
        Path dir = tmp.resolve("scan");
        BuildingEditTransactionStore.bindDirectoryForTest(dir);
        var index = new SpatialIndex();
        var house = house("scan", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        var begin = service.beginTransaction(pending(UUID.randomUUID(), UUID.randomUUID(), house, roof));
        UUID txnId = begin.transaction().transactionId();
        service.markWorldApplied(txnId);

        BuildingEditTransactionStore.clearForTest();
        BuildingEditTransactionStore.bindDirectoryForTest(dir);
        var open = BuildingEditTransactionStore.listOpen();
        assertEquals(1, open.size());
        assertEquals(BuildingEditTransaction.Status.WORLD_APPLIED, open.getFirst().status());
        assertEquals(txnId, open.getFirst().transactionId());
    }
}
