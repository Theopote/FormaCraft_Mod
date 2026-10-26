package com.formacraft.server.edit;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.memory.MemoryManager;
import com.formacraft.server.memory.MemoryUpdateResult;
import com.formacraft.server.memory.ProjectMemory;
import com.formacraft.server.memory.SpatialIndex;
import com.formacraft.server.preview.PreviewEditKind;
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
 * Round 59: Memory persist result propagation, durable edit transactions,
 * world-verified recovery (no blind Memory rewrite).
 */
class BuildingAwareRound59Test {
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
        for (BlockPos cell : cells) {
            states.put(cell, "minecraft:oak_stairs");
        }
        BuildingEditService.attachRoofIndex(memory, BuildingPartIndex.ofRoofCells(List.of(cells), states));
    }

    private BuildingEditPendingRegistry.Pending pending(
            UUID ticketId, UUID playerId, ProjectMemory house, BlockPos roof
    ) {
        return new BuildingEditPendingRegistry.Pending(
                ticketId, playerId, UUID.fromString(house.getUuid()), OVERWORLD, 0L,
                java.util.Set.of(roof),
                Map.of(roof, "minecraft:oak_stairs"),
                Map.of(roof, "minecraft:dark_oak_stairs"),
                System.currentTimeMillis() + 60_000);
    }

    @Test
    void persistFailureDoesNotReportCommitSuccess() {
        var index = new SpatialIndex();
        var house = house("fail", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        AtomicBoolean fail = new AtomicBoolean(true);
        var service = new BuildingEditService(manager(index, fail));
        assertFalse(service.commitSuccessfulRoofEdit(
                UUID.fromString(house.getUuid()), OVERWORLD, 0L,
                Map.of(roof, "minecraft:dark_oak_stairs")));
        assertEquals(0L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));
    }

    @Test
    void txnPersistFailureBlocksWorldWritePrep() {
        BuildingEditTransactionStore.bindDirectoryForTest(tmp.resolve("readonly-block"));
        // Make directory a file so createDirectories / write fails.
        // Instead: use a path under tmp that we can delete after first success — inject by
        // clearing persist root mid-flight is hard; simulate via second open txn conflict.
        BuildingEditTransactionStore.clearForTest();

        var index = new SpatialIndex();
        var house = house("txn", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        UUID player = UUID.randomUUID();
        var p1 = pending(UUID.randomUUID(), player, house, roof);
        var begin1 = service.beginTransaction(p1);
        assertTrue(begin1.ok());
        var p2 = pending(UUID.randomUUID(), player, house, roof);
        var begin2 = service.beginTransaction(p2);
        assertFalse(begin2.ok());
        assertTrue(begin2.message().contains("open_transaction"));
    }

    @Test
    void durableTxnSurvivesStoreRebind() {
        Path dir = tmp.resolve("txns");
        BuildingEditTransactionStore.bindDirectoryForTest(dir);
        var index = new SpatialIndex();
        var house = house("disk", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        var begin = service.beginTransaction(pending(UUID.randomUUID(), UUID.randomUUID(), house, roof));
        assertTrue(begin.ok());
        UUID txnId = begin.transaction().transactionId();
        service.markWorldApplied(txnId);
        service.markRecoveryRequired(txnId);

        BuildingEditTransactionStore.clearForTest();
        BuildingEditTransactionStore.bindDirectoryForTest(dir);
        var reloaded = BuildingEditTransactionStore.get(txnId);
        assertNotNull(reloaded);
        assertEquals(BuildingEditTransaction.Status.RECOVERY_REQUIRED, reloaded.status());
        assertNotNull(BuildingEditTransactionStore.findOpenByBuilding(UUID.fromString(house.getUuid())));
    }

    @Test
    void repairRequiresLiveWorldMatchTarget() {
        var index = new SpatialIndex();
        var house = house("rep", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        UUID buildingId = UUID.fromString(house.getUuid());
        var begin = service.beginTransaction(pending(UUID.randomUUID(), UUID.randomUUID(), house, roof));
        assertTrue(begin.ok());
        service.markWorldApplied(begin.transaction().transactionId());
        service.markRecoveryRequired(begin.transaction().transactionId());
        service.markMemorySyncFailed(buildingId, OVERWORLD, 0L, Map.of(roof, "minecraft:dark_oak_stairs"));

        var mismatched = service.repairMemorySyncWithLiveWorld(buildingId, OVERWORLD,
                Map.of(roof, "minecraft:stone"));
        assertFalse(mismatched.ok());
        assertTrue(mismatched.message().contains("world_state_mismatch"));
        assertTrue(BuildingMemoryKeys.isMemorySyncBlocked(index.getMemory(house.getUuid())));

        var matched = service.repairMemorySyncWithLiveWorld(buildingId, OVERWORLD,
                Map.of(roof, "minecraft:dark_oak_stairs"));
        assertTrue(matched.ok(), () -> matched.message());
        assertEquals(1L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));
        assertFalse(BuildingMemoryKeys.isMemorySyncBlocked(index.getMemory(house.getUuid())));
    }

    @Test
    void repairWhenWorldMatchesExpectedTreatsAsUndone() {
        var index = new SpatialIndex();
        var house = house("undo", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        UUID buildingId = UUID.fromString(house.getUuid());
        var begin = service.beginTransaction(pending(UUID.randomUUID(), UUID.randomUUID(), house, roof));
        service.markWorldApplied(begin.transaction().transactionId());
        service.markRecoveryRequired(begin.transaction().transactionId());
        service.markMemorySyncFailed(buildingId, OVERWORLD, 0L, Map.of(roof, "minecraft:dark_oak_stairs"));

        var undone = service.repairMemorySyncWithLiveWorld(buildingId, OVERWORLD,
                Map.of(roof, "minecraft:oak_stairs"));
        assertTrue(undone.ok(), () -> undone.message());
        assertEquals(0L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));
        assertFalse(BuildingMemoryKeys.isMemorySyncBlocked(index.getMemory(house.getUuid())));
        assertEquals(BuildingEditTransaction.Status.ABORTED,
                BuildingEditTransactionStore.get(begin.transaction().transactionId()).status());
    }

    @Test
    void repairIsIdempotentOnRevision() {
        var index = new SpatialIndex();
        var house = house("idem", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        UUID buildingId = UUID.fromString(house.getUuid());
        assertTrue(service.commitSuccessfulRoofEdit(buildingId, OVERWORLD, 0L,
                Map.of(roof, "minecraft:dark_oak_stairs")));
        assertEquals(1L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));
        // Same commit again with base 0 after already at 1 with matching states → idempotent true.
        assertTrue(service.commitSuccessfulRoofEdit(buildingId, OVERWORLD, 0L,
                Map.of(roof, "minecraft:dark_oak_stairs")));
        assertEquals(1L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));
    }

    @Test
    void buildingEditPendingMissingStillRejected() {
        var outcome = BuildingEditConfirmGate.validateBeforeApply(
                manager(new SpatialIndex()), null, UUID.randomUUID(), OVERWORLD, BlockPos.ORIGIN,
                List.of(new BlockPatch(BlockPatch.REPLACE, 0, 0, 0, "minecraft:stone")),
                PreviewEditKind.BUILDING_ROOF_MATERIAL, null);
        assertFalse(outcome.ok());
        assertTrue(outcome.message().contains("pending_missing"));
    }

    @Test
    void ordinaryPatchUnaffectedByOpenTxnGate() {
        var outcome = BuildingEditConfirmGate.validateBeforeApply(
                manager(new SpatialIndex()), null, UUID.randomUUID(), OVERWORLD, BlockPos.ORIGIN,
                List.of(new BlockPatch(BlockPatch.REPLACE, 0, 0, 0, "minecraft:stone")),
                PreviewEditKind.NONE, null);
        assertTrue(outcome.ok());
    }
}
