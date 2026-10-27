package com.formacraft.server.edit;

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
 * Round 61: recovery must capture live world before closing a txn;
 * Memory-already-committed + later player edits must not silently finalize.
 */
class BuildingAwareRound61Test {
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
                index.removeMemory(memory.getUuid());
                index.addMemory(memory);
                return MemoryUpdateResult.succeeded();
            }
            @Override protected void persistMemory(ProjectMemory memory) throws IOException {}
        };
    }

    private void attachOakRoof(ProjectMemory memory, BlockPos... cells) {
        Map<BlockPos, String> states = new HashMap<>();
        for (BlockPos cell : cells) states.put(cell, "minecraft:oak_stairs");
        BuildingEditService.attachRoofIndex(memory, BuildingPartIndex.ofRoofCells(List.of(cells), states));
    }

    private BuildingEditTransaction openWorldApplied(
            BuildingEditService service, ProjectMemory house, BlockPos roof, boolean commitMemory
    ) {
        BuildingEditTransactionStore.bindDirectoryForTest(tmp.resolve("txn-" + UUID.randomUUID()));
        var begin = service.beginTransaction(new BuildingEditPendingRegistry.Pending(
                UUID.randomUUID(), UUID.randomUUID(), UUID.fromString(house.getUuid()), OVERWORLD, 0L,
                java.util.Set.of(roof),
                Map.of(roof, "minecraft:oak_stairs"),
                Map.of(roof, "minecraft:dark_oak_stairs"),
                System.currentTimeMillis() + 60_000));
        assertTrue(begin.ok());
        UUID txnId = begin.transaction().transactionId();
        assertTrue(service.markWorldApplied(txnId));
        if (commitMemory) {
            assertTrue(service.commitSuccessfulRoofEdit(
                    UUID.fromString(house.getUuid()), OVERWORLD, 0L,
                    Map.of(roof, "minecraft:dark_oak_stairs")));
            BuildingEditTransactionStore.failNextPersistForTest();
            assertFalse(service.markMemoryCommitted(txnId));
            assertEquals(BuildingEditTransaction.Status.WORLD_APPLIED,
                    BuildingEditTransactionStore.get(txnId).status());
        }
        return BuildingEditTransactionStore.get(txnId);
    }

    @Test
    void memoryCommittedWorldStillTargetFinalizesViaCoordinator() {
        var index = new SpatialIndex();
        var house = house("ok", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var mm = manager(index);
        var service = new BuildingEditService(mm);
        var txn = openWorldApplied(service, house, roof, true);

        var result = BuildingEditRecoveryCoordinator.recoverWithLiveSnapshot(
                service, mm, txn, Map.of(roof, "minecraft:dark_oak_stairs"));
        assertTrue(result.ok(), () -> result.message());
        assertEquals("finalized_memory_already_committed", result.message());
        assertEquals(BuildingEditTransaction.Status.MEMORY_COMMITTED,
                BuildingEditTransactionStore.get(txn.transactionId()).status());
        assertEquals(1L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));
    }

    @Test
    void memoryCommittedThenPlayerEditsWorldKeepsTxnOpen() {
        var index = new SpatialIndex();
        var house = house("div", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var mm = manager(index);
        var service = new BuildingEditService(mm);
        var txn = openWorldApplied(service, house, roof, true);

        // Player replaced the successful dark-oak roof with stone after Memory commit.
        var result = BuildingEditRecoveryCoordinator.recoverWithLiveSnapshot(
                service, mm, txn, Map.of(roof, "minecraft:stone"));
        assertFalse(result.ok());
        assertEquals("committed_memory_world_diverged_keep_open", result.message());
        assertNotNull(BuildingEditTransactionStore.findOpenByBuilding(UUID.fromString(house.getUuid())));
        assertEquals(1L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));
        // Must not have closed as MEMORY_COMMITTED.
        assertNotEquals(BuildingEditTransaction.Status.MEMORY_COMMITTED,
                BuildingEditTransactionStore.get(txn.transactionId()).status());
    }

    @Test
    void memoryCommittedWorldUndoneKeepsOpen() {
        var index = new SpatialIndex();
        var house = house("undo", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var mm = manager(index);
        var service = new BuildingEditService(mm);
        var txn = openWorldApplied(service, house, roof, true);

        var result = BuildingEditRecoveryCoordinator.recoverWithLiveSnapshot(
                service, mm, txn, Map.of(roof, "minecraft:oak_stairs"));
        assertFalse(result.ok());
        assertEquals("memory_ahead_world_undone_keep_open", result.message());
        assertNotNull(BuildingEditTransactionStore.findOpenByBuilding(UUID.fromString(house.getUuid())));
        assertEquals(1L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));
    }

    @Test
    void preparedWithPartialWorldDriftStaysBlocked() {
        var index = new SpatialIndex();
        var house = house("prep", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var mm = manager(index);
        var service = new BuildingEditService(mm);
        BuildingEditTransactionStore.bindDirectoryForTest(tmp.resolve("prep"));
        var begin = service.beginTransaction(new BuildingEditPendingRegistry.Pending(
                UUID.randomUUID(), UUID.randomUUID(), UUID.fromString(house.getUuid()), OVERWORLD, 0L,
                java.util.Set.of(roof),
                Map.of(roof, "minecraft:oak_stairs"),
                Map.of(roof, "minecraft:dark_oak_stairs"),
                System.currentTimeMillis() + 60_000));
        assertEquals(BuildingEditTransaction.Status.PREPARED, begin.transaction().status());

        var result = BuildingEditRecoveryCoordinator.recoverWithLiveSnapshot(
                service, mm, begin.transaction(), Map.of(roof, "minecraft:cobblestone"));
        assertFalse(result.ok());
        assertEquals("prepared_world_drift", result.message());
        assertEquals(BuildingEditTransaction.Status.RECOVERY_REQUIRED,
                BuildingEditTransactionStore.get(begin.transaction().transactionId()).status());
    }

    @Test
    void preparedWorldUnchangedAborts() {
        var index = new SpatialIndex();
        var house = house("clean", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var mm = manager(index);
        var service = new BuildingEditService(mm);
        BuildingEditTransactionStore.bindDirectoryForTest(tmp.resolve("clean"));
        var begin = service.beginTransaction(new BuildingEditPendingRegistry.Pending(
                UUID.randomUUID(), UUID.randomUUID(), UUID.fromString(house.getUuid()), OVERWORLD, 0L,
                java.util.Set.of(roof),
                Map.of(roof, "minecraft:oak_stairs"),
                Map.of(roof, "minecraft:dark_oak_stairs"),
                System.currentTimeMillis() + 60_000));

        var result = BuildingEditRecoveryCoordinator.recoverWithLiveSnapshot(
                service, mm, begin.transaction(), Map.of(roof, "minecraft:oak_stairs"));
        assertTrue(result.ok(), () -> result.message());
        assertEquals("prepared_aborted_world_unchanged", result.message());
        assertEquals(BuildingEditTransaction.Status.ABORTED,
                BuildingEditTransactionStore.get(begin.transaction().transactionId()).status());
        assertEquals(0L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));
    }

    @Test
    void corruptTxnMissingTargetsRefused() {
        var index = new SpatialIndex();
        var house = house("bad", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var mm = manager(index);
        var service = new BuildingEditService(mm);
        var corrupt = new BuildingEditTransaction(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.fromString(house.getUuid()), OVERWORLD, 0L,
                BuildingEditTransaction.Operation.REPLACE_ROOF_MATERIAL,
                Map.of(roof, "minecraft:oak_stairs"),
                Map.of(), // missing targets
                BuildingEditTransaction.Status.WORLD_APPLIED,
                System.currentTimeMillis(), System.currentTimeMillis());
        var result = BuildingEditRecoveryCoordinator.recoverWithLiveSnapshot(
                service, mm, corrupt, Map.of(roof, "minecraft:dark_oak_stairs"));
        assertFalse(result.ok());
        assertTrue(result.message().startsWith("txn_corrupt:"));
    }

    @Test
    void repeatedRecoveryIsIdempotentOnSuccess() {
        var index = new SpatialIndex();
        var house = house("idem", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var mm = manager(index);
        var service = new BuildingEditService(mm);
        var txn = openWorldApplied(service, house, roof, true);
        Map<BlockPos, String> live = Map.of(roof, "minecraft:dark_oak_stairs");

        var first = BuildingEditRecoveryCoordinator.recoverWithLiveSnapshot(service, mm, txn, live);
        assertTrue(first.ok(), () -> first.message());
        assertEquals(1L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));

        // Second pass: txn already MEMORY_COMMITTED — listOpen won't include it; direct call on closed txn.
        var closed = BuildingEditTransactionStore.get(txn.transactionId());
        assertEquals(BuildingEditTransaction.Status.MEMORY_COMMITTED, closed.status());
        var second = service.finalizeCommittedTransaction(txn.transactionId());
        assertTrue(second.ok());
        assertEquals(1L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));
    }

    @Test
    void restartReloadThenCoordinatorScanUsesWorldGate() {
        Path dir = tmp.resolve("reload");
        BuildingEditTransactionStore.bindDirectoryForTest(dir);
        var index = new SpatialIndex();
        var house = house("scan", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var mm = manager(index);
        var service = new BuildingEditService(mm);
        UUID buildingId = UUID.fromString(house.getUuid());
        var begin = service.beginTransaction(new BuildingEditPendingRegistry.Pending(
                UUID.randomUUID(), UUID.randomUUID(), buildingId, OVERWORLD, 0L,
                java.util.Set.of(roof),
                Map.of(roof, "minecraft:oak_stairs"),
                Map.of(roof, "minecraft:dark_oak_stairs"),
                System.currentTimeMillis() + 60_000));
        UUID txnId = begin.transaction().transactionId();
        assertTrue(service.markWorldApplied(txnId));
        assertTrue(service.commitSuccessfulRoofEdit(
                buildingId, OVERWORLD, 0L, Map.of(roof, "minecraft:dark_oak_stairs")));
        BuildingEditTransactionStore.failNextPersistForTest();
        assertFalse(service.markMemoryCommitted(txnId));

        BuildingEditTransactionStore.clearForTest();
        BuildingEditTransactionStore.bindDirectoryForTest(dir);
        var reloaded = BuildingEditTransactionStore.get(txnId);
        assertNotNull(reloaded);
        assertEquals(BuildingEditTransaction.Status.WORLD_APPLIED, reloaded.status());

        // Diverged world after reload must keep open (61-01).
        var blocked = BuildingEditRecoveryCoordinator.recoverWithLiveSnapshot(
                service, mm, reloaded, Map.of(roof, "minecraft:stone_bricks"));
        assertFalse(blocked.ok());
        assertEquals("committed_memory_world_diverged_keep_open", blocked.message());

        // Matching world then finalizes.
        var ok = BuildingEditRecoveryCoordinator.recoverWithLiveSnapshot(
                service, mm, BuildingEditTransactionStore.get(txnId),
                Map.of(roof, "minecraft:dark_oak_stairs"));
        assertTrue(ok.ok(), () -> ok.message());
        assertNull(BuildingEditTransactionStore.findOpenByBuilding(buildingId));
    }
}
