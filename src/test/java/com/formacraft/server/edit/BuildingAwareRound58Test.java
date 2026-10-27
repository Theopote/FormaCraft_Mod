package com.formacraft.server.edit;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.memory.MemoryManager;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round 58: building-edit Ticket must not degrade when Pending is missing;
 * Memory sync failure blocks further incremental edits until repaired.
 */
class BuildingAwareRound58Test {
    private static final Identifier OVERWORLD = Identifier.of("minecraft:overworld");

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
            @Override public com.formacraft.server.memory.MemoryUpdateResult updateMemoryStrict(ProjectMemory memory) {
                index.removeMemory(memory.getUuid());
                index.addMemory(memory);
                return com.formacraft.server.memory.MemoryUpdateResult.succeeded();
            }
        };
    }

    private void attachOakRoof(ProjectMemory memory, BlockPos... cells) {
        Map<BlockPos, String> states = new HashMap<>();
        for (BlockPos cell : cells) {
            states.put(cell, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        }
        BuildingEditService.attachRoofIndex(memory, BuildingPartIndex.ofRoofCells(List.of(cells), states));
    }

    @Test
    void buildingEditTicketWithoutPendingIsRejected() {
        var patches = List.of(new BlockPatch(BlockPatch.REPLACE, 1, 5, 1, "minecraft:dark_oak_stairs"));
        var outcome = BuildingEditConfirmGate.validateBeforeApply(
                manager(new SpatialIndex()),
                null,
                UUID.randomUUID(),
                OVERWORLD,
                BlockPos.ORIGIN,
                patches,
                PreviewEditKind.BUILDING_ROOF_MATERIAL,
                null);
        assertFalse(outcome.ok());
        assertTrue(outcome.message().contains("pending_missing"));
    }

    @Test
    void ordinaryPatchTicketWithoutPendingPassesGate() {
        var patches = List.of(new BlockPatch(BlockPatch.REPLACE, 0, 0, 0, "minecraft:stone"));
        var outcome = BuildingEditConfirmGate.validateBeforeApply(
                manager(new SpatialIndex()),
                null,
                UUID.randomUUID(),
                OVERWORLD,
                BlockPos.ORIGIN,
                patches,
                PreviewEditKind.NONE,
                null);
        assertTrue(outcome.ok());
        assertNull(outcome.pending());
    }

    @Test
    void expiredPendingWhileTicketKindRequiresEditIsRejected() {
        var index = new SpatialIndex();
        var house = house("exp", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        UUID ticketId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        String state = "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]";
        BuildingEditPendingRegistry.put(BuildingEditPendingRegistry.Pending.roof(
                ticketId, playerId, UUID.fromString(house.getUuid()), OVERWORLD, 0L,
                java.util.Set.of(roof), Map.of(roof, state),
                Map.of(roof, "minecraft:dark_oak_stairs"),
                System.currentTimeMillis() - 1));
        assertNull(BuildingEditPendingRegistry.get(ticketId), "expired pending must vanish");

        var patches = List.of(new BlockPatch(BlockPatch.REPLACE, 1, 5, 1, "minecraft:dark_oak_stairs"));
        var outcome = BuildingEditConfirmGate.validateBeforeApply(
                manager(index), null, playerId, OVERWORLD, new BlockPos(0, 64, 0), patches,
                PreviewEditKind.BUILDING_ROOF_MATERIAL, null);
        assertFalse(outcome.ok());
        assertTrue(outcome.message().contains("pending_missing"));
    }

    @Test
    void foreignPlayerCannotDropOwnedPending() {
        UUID ticketId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        var house = house("own", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        String state = "minecraft:oak_stairs";
        BuildingEditPendingRegistry.put(BuildingEditPendingRegistry.Pending.roof(
                ticketId, owner, UUID.fromString(house.getUuid()), OVERWORLD, 0L,
                java.util.Set.of(roof), Map.of(roof, state), Map.of(roof, "minecraft:dark_oak_stairs"),
                System.currentTimeMillis() + 60_000));
        assertFalse(BuildingEditPendingRegistry.dropOwned(ticketId, attacker));
        assertNotNull(BuildingEditPendingRegistry.get(ticketId));
        assertFalse(PreviewTicketStorage.cancel(ticketId, attacker));
        assertNotNull(BuildingEditPendingRegistry.get(ticketId));
    }

    @Test
    void memorySyncFailureBlocksFurtherRoofEditsUntilRepaired() {
        var index = new SpatialIndex();
        var house = house("sync", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var mgr = manager(index);
        var service = new BuildingEditService(mgr);
        UUID buildingId = UUID.fromString(house.getUuid());

        service.markMemorySyncFailed(buildingId, OVERWORLD, 0L,
                Map.of(roof, "minecraft:dark_oak_stairs"));
        assertTrue(BuildingMemoryKeys.isMemorySyncBlocked(mgr.getMemory(house.getUuid())));

        var prepared = service.prepareRoofMaterialReplace(
                new BuildingTargetResolver.Query(OVERWORLD, buildingId, null, null, null, 16),
                "spruce",
                Map.of(roof, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"),
                null);
        assertFalse(prepared.ok());
        assertTrue(prepared.refusals().getFirst().contains("Memory 同步")
                || prepared.refusals().getFirst().contains("EDIT_MEMORY_SYNC"));

        var repair = service.repairMemorySyncWithLiveWorld(buildingId, OVERWORLD,
                Map.of(roof, "minecraft:dark_oak_stairs"));
        assertTrue(repair.ok(), () -> repair.message());
        assertFalse(BuildingMemoryKeys.isMemorySyncBlocked(mgr.getMemory(house.getUuid())));
        assertEquals(1L, BuildingMemoryKeys.revisionOf(mgr.getMemory(house.getUuid())));

        var preparedAgain = service.prepareRoofMaterialReplace(
                new BuildingTargetResolver.Query(OVERWORLD, buildingId, null, null, null, 16),
                "spruce",
                Map.of(roof, "minecraft:dark_oak_stairs"),
                null);
        // May refuse for material/live mismatch depending on recorded state; must not be sync-blocked.
        assertTrue(preparedAgain.refusals().stream().noneMatch(r -> r.contains("EDIT_MEMORY_SYNC")));
    }

    @Test
    void commitClearsMemorySyncBlock() {
        var index = new SpatialIndex();
        var house = house("clr", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var mgr = manager(index);
        var service = new BuildingEditService(mgr);
        UUID buildingId = UUID.fromString(house.getUuid());
        service.markMemorySyncFailed(buildingId, OVERWORLD, 0L, Map.of(roof, "minecraft:dark_oak_stairs"));
        assertTrue(service.commitSuccessfulRoofEdit(buildingId, OVERWORLD, 0L,
                Map.of(roof, "minecraft:dark_oak_stairs")));
        assertFalse(BuildingMemoryKeys.isMemorySyncBlocked(mgr.getMemory(house.getUuid())));
    }

    @Test
    void revisionChangedBeforeConfirmStillRejected() {
        var index = new SpatialIndex();
        var house = house("rev", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var mgr = manager(index);
        UUID ticketId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        String state = "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]";
        var pending = BuildingEditPendingRegistry.Pending.roof(
                ticketId, playerId, UUID.fromString(house.getUuid()), OVERWORLD, 0L,
                java.util.Set.of(roof), Map.of(roof, state),
                Map.of(roof, "minecraft:dark_oak_stairs"),
                System.currentTimeMillis() + 60_000);
        BuildingMemoryKeys.bumpRevision(house);
        mgr.updateMemory(house);
        var patches = List.of(new BlockPatch(BlockPatch.REPLACE, 1, 5, 1, "minecraft:dark_oak_stairs"));
        var outcome = BuildingEditConfirmGate.validateBeforeApply(
                mgr, null, playerId, OVERWORLD, new BlockPos(0, 64, 0), patches,
                PreviewEditKind.BUILDING_ROOF_MATERIAL, pending);
        assertFalse(outcome.ok());
        assertTrue(outcome.message().contains("revision_mismatch"));
    }
}
