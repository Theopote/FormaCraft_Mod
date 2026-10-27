package com.formacraft.server.edit;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.memory.MemoryManager;
import com.formacraft.server.memory.MemoryUpdateResult;
import com.formacraft.server.memory.ProjectMemory;
import com.formacraft.server.memory.SpatialIndex;
import com.formacraft.server.preview.PreviewEditKind;
import com.formacraft.server.preview.PreviewTicketStorage;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round 62: WindowInsert / WindowSeal share Building-Aware ConfirmGate + durable txn + revision.
 */
class BuildingAwareRound62Test {
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

    private BuildingEditPendingRegistry.Pending windowPending(
            BuildingEditOperation op,
            UUID ticketId,
            UUID playerId,
            ProjectMemory house,
            BlockPos cell,
            String expected,
            String target
    ) {
        return new BuildingEditPendingRegistry.Pending(
                ticketId, playerId, UUID.fromString(house.getUuid()), OVERWORLD, 0L,
                op, Set.of(cell), Map.of(cell, expected), Map.of(cell, target),
                System.currentTimeMillis() + 60_000);
    }

    @Test
    void insertWindowCommitRecordsGlassAndBumpsSharedRevision() {
        var index = new SpatialIndex();
        var house = house("win-ins", new BlockPos(0, 64, 0), new BlockPos(8, 70, 8));
        BlockPos roof = new BlockPos(2, 69, 2);
        BlockPos wall = new BlockPos(4, 66, 0);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        UUID buildingId = UUID.fromString(house.getUuid());

        assertTrue(service.commitSuccessfulBuildingEdit(
                buildingId, OVERWORLD, 0L, BuildingEditOperation.INSERT_WINDOW,
                Map.of(wall, "minecraft:glass")));

        ProjectMemory after = index.getMemory(house.getUuid());
        assertEquals(1L, BuildingMemoryKeys.revisionOf(after));
        BuildingPartIndex parts = BuildingPartIndex.fromMemory(after);
        assertTrue(parts.windows().cells().contains(wall));
        assertEquals("minecraft:glass", parts.windows().recordedStates().get(wall));
        assertTrue(parts.roof().cells().contains(roof), "roof index must survive window commit");
    }

    @Test
    void sealWindowCommitMovesCellToWallIndex() {
        var index = new SpatialIndex();
        var house = house("win-seal", new BlockPos(0, 64, 0), new BlockPos(8, 70, 8));
        BlockPos roof = new BlockPos(2, 69, 2);
        BlockPos pane = new BlockPos(4, 66, 0);
        attachOakRoof(house, roof);
        new BuildingPartIndex(
                BuildingPartIndex.fromMemory(house).roof(),
                new BuildingPartIndex.WallPart("wall", Set.of(), Map.of()),
                new BuildingPartIndex.WindowPart("window", Set.of(pane), Map.of(pane, "minecraft:glass"))
        ).persistTo(house);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        UUID buildingId = UUID.fromString(house.getUuid());

        assertTrue(service.commitSuccessfulBuildingEdit(
                buildingId, OVERWORLD, 0L, BuildingEditOperation.SEAL_WINDOW,
                Map.of(pane, "minecraft:white_terracotta")));

        BuildingPartIndex parts = BuildingPartIndex.fromMemory(index.getMemory(house.getUuid()));
        assertEquals(1L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));
        assertFalse(parts.windows().cells().contains(pane));
        assertTrue(parts.walls().cells().contains(pane));
        assertEquals("minecraft:white_terracotta", parts.walls().recordedStates().get(pane));
    }

    @Test
    void roofCommitPreservesExistingWindowIndex() {
        var index = new SpatialIndex();
        var house = house("roof-keep-win", new BlockPos(0, 64, 0), new BlockPos(8, 70, 8));
        BlockPos roof = new BlockPos(2, 69, 2);
        BlockPos pane = new BlockPos(4, 66, 0);
        attachOakRoof(house, roof);
        BuildingPartIndex.fromMemory(house)
                .withWindowTargets(BuildingEditOperation.INSERT_WINDOW, Map.of(pane, "minecraft:glass"))
                .persistTo(house);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));

        assertTrue(service.commitSuccessfulRoofEdit(
                UUID.fromString(house.getUuid()), OVERWORLD, 0L,
                Map.of(roof, "minecraft:dark_oak_stairs")));

        BuildingPartIndex parts = BuildingPartIndex.fromMemory(index.getMemory(house.getUuid()));
        assertEquals(1L, BuildingMemoryKeys.revisionOf(index.getMemory(house.getUuid())));
        assertTrue(parts.windows().cells().contains(pane));
        assertEquals("minecraft:dark_oak_stairs", parts.roof().recordedStates().get(roof));
    }

    @Test
    void windowTxnStoresInsertOperationAndBlocksSecondBegin() {
        BuildingEditTransactionStore.bindDirectoryForTest(tmp.resolve("win-txn"));
        var index = new SpatialIndex();
        var house = house("txn-win", new BlockPos(0, 64, 0), new BlockPos(8, 70, 8));
        BlockPos roof = new BlockPos(2, 69, 2);
        BlockPos wall = new BlockPos(4, 66, 0);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));

        var pending = windowPending(
                BuildingEditOperation.INSERT_WINDOW,
                UUID.randomUUID(), UUID.randomUUID(), house, wall,
                "minecraft:white_terracotta", "minecraft:glass");
        var begin = service.beginTransaction(pending);
        assertTrue(begin.ok());
        assertEquals(BuildingEditTransaction.Operation.INSERT_WINDOW, begin.transaction().operation());

        var second = service.beginTransaction(windowPending(
                BuildingEditOperation.SEAL_WINDOW,
                UUID.randomUUID(), UUID.randomUUID(), house, wall,
                "minecraft:glass", "minecraft:white_terracotta"));
        assertFalse(second.ok());
        assertTrue(second.message().contains("open_transaction"));
    }

    @Test
    void confirmGateRejectsOperationMismatchAndRoofOverlap() {
        var index = new SpatialIndex();
        var house = house("gate", new BlockPos(0, 64, 0), new BlockPos(8, 70, 8));
        BlockPos roof = new BlockPos(2, 69, 2);
        BlockPos wall = new BlockPos(4, 66, 0);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var mgr = manager(index);
        UUID player = UUID.randomUUID();
        UUID ticket = UUID.randomUUID();

        var pending = windowPending(
                BuildingEditOperation.INSERT_WINDOW, ticket, player, house, wall,
                "minecraft:white_terracotta", "minecraft:glass");
        BuildingEditPendingRegistry.put(pending);

        var mismatch = BuildingEditConfirmGate.validateBeforeApply(
                mgr, null, player, OVERWORLD, house.getBounds().getMinPos(),
                List.of(new BlockPatch(BlockPatch.REPLACE, 4, 2, 0, "minecraft:glass")),
                PreviewEditKind.BUILDING_ROOF_MATERIAL, pending);
        assertFalse(mismatch.ok());
        assertTrue(mismatch.message().contains("operation_mismatch"));

        var roofOverlap = new BuildingEditPendingRegistry.Pending(
                UUID.randomUUID(), player, UUID.fromString(house.getUuid()), OVERWORLD, 0L,
                BuildingEditOperation.INSERT_WINDOW,
                Set.of(roof), Map.of(roof, "minecraft:oak_stairs"), Map.of(roof, "minecraft:glass"),
                System.currentTimeMillis() + 60_000);
        var overlap = BuildingEditConfirmGate.validateBeforeApply(
                mgr, null, player, OVERWORLD, house.getBounds().getMinPos(),
                List.of(new BlockPatch(BlockPatch.REPLACE, 2, 5, 2, "minecraft:glass")),
                PreviewEditKind.BUILDING_WINDOW_INSERT, roofOverlap);
        assertFalse(overlap.ok());
        assertTrue(overlap.message().contains("window_overlaps_roof"));
    }

    @Test
    void prepareWindowInsertRefusesWhenOpenTxnExists() {
        BuildingEditTransactionStore.bindDirectoryForTest(tmp.resolve("prep-block"));
        var index = new SpatialIndex();
        var house = house("blocked", new BlockPos(0, 64, 0), new BlockPos(8, 70, 8));
        BlockPos roof = new BlockPos(2, 69, 2);
        BlockPos wall = new BlockPos(4, 66, 0);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        assertTrue(service.beginTransaction(windowPending(
                BuildingEditOperation.INSERT_WINDOW,
                UUID.randomUUID(), UUID.randomUUID(), house, wall,
                "minecraft:white_terracotta", "minecraft:glass")).ok());

        Map<BlockPos, net.minecraft.block.BlockState> live = Map.of(
                wall, Blocks.WHITE_TERRACOTTA.getDefaultState());
        var prepared = service.prepareWindowInsert(
                new BuildingTargetResolver.Query(OVERWORLD, UUID.fromString(house.getUuid()),
                        null, null, new BlockPos(4, 64, 4), 32),
                live, 2, 2);
        assertFalse(prepared.ok());
        assertTrue(prepared.refusals().stream().anyMatch(s -> s.contains("未完成的编辑事务")));
    }

    @Test
    void windowFinalizeCommittedIsIdempotentWhenTargetsRecorded() {
        BuildingEditTransactionStore.bindDirectoryForTest(tmp.resolve("win-fin"));
        var index = new SpatialIndex();
        var house = house("fin", new BlockPos(0, 64, 0), new BlockPos(8, 70, 8));
        BlockPos roof = new BlockPos(2, 69, 2);
        BlockPos wall = new BlockPos(4, 66, 0);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        UUID buildingId = UUID.fromString(house.getUuid());

        var begin = service.beginTransaction(windowPending(
                BuildingEditOperation.INSERT_WINDOW,
                UUID.randomUUID(), UUID.randomUUID(), house, wall,
                "minecraft:white_terracotta", "minecraft:glass"));
        UUID txnId = begin.transaction().transactionId();
        assertTrue(service.markWorldApplied(txnId));
        assertTrue(service.commitSuccessfulBuildingEdit(
                buildingId, OVERWORLD, 0L, BuildingEditOperation.INSERT_WINDOW,
                Map.of(wall, "minecraft:glass")));

        var finalized = service.finalizeCommittedTransaction(txnId);
        assertTrue(finalized.ok(), () -> finalized.message());
        assertEquals(BuildingEditTransaction.Status.MEMORY_COMMITTED,
                BuildingEditTransactionStore.get(txnId).status());
        assertTrue(BuildingEditRecoveryCoordinator.memoryLooksCommitted(
                manager(index), BuildingEditTransactionStore.get(txnId)));
    }

    @Test
    void previewEditKindMapsWindowOperations() {
        assertEquals(PreviewEditKind.BUILDING_WINDOW_INSERT,
                PreviewEditKind.of(BuildingEditOperation.INSERT_WINDOW));
        assertEquals(PreviewEditKind.BUILDING_WINDOW_SEAL,
                PreviewEditKind.of(BuildingEditOperation.SEAL_WINDOW));
        assertEquals(BuildingEditOperation.INSERT_WINDOW,
                PreviewEditKind.BUILDING_WINDOW_INSERT.toOperation());
        assertEquals(BuildingEditOperation.SEAL_WINDOW,
                PreviewEditKind.BUILDING_WINDOW_SEAL.toOperation());
    }
}
