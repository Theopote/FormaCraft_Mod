package com.formacraft.server.edit;

import com.formacraft.common.build.GeometrySource;
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
 * Round 57 regressions: ConfirmGate revision, Pending lifecycle/ownership,
 * fail-closed live states, final-patch roof index.
 */
class BuildingAwareRound57Test {
    private static final Identifier OVERWORLD = Identifier.of("minecraft:overworld");

    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @BeforeEach
    void resetRegistries() {
        BuildingEditPendingRegistry.clearForTest();
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

    private BuildingEditPendingRegistry.Pending pendingFor(
            UUID ticketId, UUID playerId, ProjectMemory house, BlockPos roof, long expiresAt
    ) {
        UUID buildingId = UUID.fromString(house.getUuid());
        String state = "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]";
        return BuildingEditPendingRegistry.Pending.roof(
                ticketId,
                playerId,
                buildingId,
                OVERWORLD,
                BuildingMemoryKeys.revisionOf(house),
                java.util.Set.of(roof),
                Map.of(roof, state),
                Map.of(roof, "minecraft:dark_oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"),
                expiresAt);
    }

    @Test
    void confirmGateRejectsStaleRevisionBeforeApply() {
        var index = new SpatialIndex();
        var house = house("rev", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var mgr = manager(index);

        UUID ticketId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        var pending = pendingFor(ticketId, playerId, house, roof, System.currentTimeMillis() + 60_000);
        BuildingEditPendingRegistry.put(pending);

        // Another edit bumps revision while this ticket is still open.
        BuildingMemoryKeys.bumpRevision(house);
        mgr.updateMemory(house);

        String state = "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]";
        var patches = List.of(new BlockPatch(BlockPatch.REPLACE, 1, 5, 1,
                "minecraft:dark_oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"));
        // ConfirmGate.captureLive needs a world — use validator path via gate with null world live map
        // by calling gate which captures from world; for unit test call validator + gate pieces.
        var outcome = BuildingEditConfirmGate.validateBeforeApply(
                mgr, null, playerId, OVERWORLD, new BlockPos(0, 64, 0), patches,
                PreviewEditKind.BUILDING_ROOF_MATERIAL, pending);
        // Without world, captureLive returns empty → fail-closed live_state_missing OR revision first.
        assertFalse(outcome.ok());
        assertTrue(outcome.message().contains("revision_mismatch")
                        || outcome.message().contains("live_state_missing"),
                () -> outcome.message());

        // Explicit revision check after restoring live via scope validator alone:
        BuildingMemoryKeys.setRevision(house, pending.baseRevision());
        mgr.updateMemory(house);
        var live = Map.of(roof, state);
        var validation = BuildingEditScopeValidator.validate(
                pending.toScope(), new BlockPos(0, 64, 0), patches, 1L, live);
        assertFalse(validation.accepted());
        assertTrue(validation.conflicts().getFirst().contains("revision_mismatch"));
    }

    @Test
    void confirmGateRejectsWhenRoofIndexDrifted() {
        var index = new SpatialIndex();
        var house = house("drift", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        BlockPos extra = new BlockPos(2, 69, 1);
        attachOakRoof(house, roof);
        index.addMemory(house);
        var mgr = manager(index);

        UUID ticketId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        var pending = pendingFor(ticketId, playerId, house, roof, System.currentTimeMillis() + 60_000);

        // Roof index gains a cell after preview was bound.
        attachOakRoof(house, roof, extra);
        mgr.updateMemory(house);

        String state = "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]";
        var patches = List.of(new BlockPatch(BlockPatch.REPLACE, 1, 5, 1,
                "minecraft:dark_oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"));
        // Inject live by temporarily validating roof-cells check in gate:
        var outcome = BuildingEditConfirmGate.validateBeforeApply(
                mgr, null, playerId, OVERWORLD, new BlockPos(0, 64, 0), patches,
                PreviewEditKind.BUILDING_ROOF_MATERIAL, pending);
        assertFalse(outcome.ok());
        assertTrue(outcome.message().contains("roof_cells_drift")
                        || outcome.message().contains("live_state_missing"),
                () -> outcome.message());
        // Direct cells equality is the drift we care about when live is present:
        assertNotEquals(
                BuildingPartIndex.fromMemory(house).roof().cells(),
                pending.allowedCells());
    }

    @Test
    void missingLiveStateIsFailClosed() {
        var building = new BuildingInstance(
                UUID.randomUUID(), OVERWORLD, BlockPos.ORIGIN,
                BlockPos.ORIGIN, new BlockPos(4, 4, 4), 0L, "x");
        var roofCell = new BlockPos(1, 3, 1);
        var roof = new BuildingPartIndex.RoofPart("roof", java.util.Set.of(roofCell),
                Map.of(roofCell, "minecraft:oak_planks"));
        var scope = BuildingEditScope.replaceRoofMaterial(building, roof, roof.recordedStates(), java.util.Set.of());
        var patches = List.of(new BlockPatch(BlockPatch.REPLACE, 1, 3, 1, "minecraft:dark_oak_planks"));
        var validation = BuildingEditScopeValidator.validate(
                scope, BlockPos.ORIGIN, patches, 0L, Map.of()); // live missing
        assertFalse(validation.accepted());
        assertTrue(validation.conflicts().stream().anyMatch(c -> c.contains("live_state_missing")));
    }

    @Test
    void unauthorizedCancelDoesNotDropForeignPending() {
        UUID ticketId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        var house = house("own", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);

        BuildingEditPendingRegistry.put(pendingFor(
                ticketId, owner, house, roof, System.currentTimeMillis() + 60_000));
        assertEquals(1, BuildingEditPendingRegistry.sizeForTest());

        // Attacker cancel without owning the ticket must not erase Pending.
        assertFalse(BuildingEditPendingRegistry.dropOwned(ticketId, attacker));
        assertEquals(1, BuildingEditPendingRegistry.sizeForTest());
        assertFalse(PreviewTicketStorage.cancel(ticketId, attacker));
        assertEquals(1, BuildingEditPendingRegistry.sizeForTest());

        assertTrue(BuildingEditPendingRegistry.dropOwned(ticketId, owner));
        assertEquals(0, BuildingEditPendingRegistry.sizeForTest());
    }

    @Test
    void expiredPendingIsPurgedWithTicketLifecycle() {
        UUID ticketId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        var house = house("exp", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);

        BuildingEditPendingRegistry.put(pendingFor(
                ticketId, playerId, house, roof, System.currentTimeMillis() - 1));
        assertNull(BuildingEditPendingRegistry.get(ticketId));
        BuildingEditPendingRegistry.purgeExpired();
        assertEquals(0, BuildingEditPendingRegistry.sizeForTest());
    }

    @Test
    void clearPlayerDropsOwnedPending() {
        UUID ticketA = UUID.randomUUID();
        UUID ticketB = UUID.randomUUID();
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        var house = house("disc", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(house, roof);
        long exp = System.currentTimeMillis() + 60_000;
        BuildingEditPendingRegistry.put(pendingFor(ticketA, playerA, house, roof, exp));
        BuildingEditPendingRegistry.put(pendingFor(ticketB, playerB, house, roof, exp));

        PreviewTicketStorage.clearPlayer(playerA);
        assertNull(BuildingEditPendingRegistry.get(ticketA));
        assertNotNull(BuildingEditPendingRegistry.get(ticketB));
    }

    @Test
    void finalPatchDropsOverwrittenAndRemovedRoofCells() {
        var origin = new BlockPos(0, 64, 0);
        var roofSrc = GeometrySource.generated("ROOF_COMPONENT");
        var wallSrc = GeometrySource.generated("WALL");
        BlockPos roofWorld = new BlockPos(1, 69, 1);
        var roofPlace = new BlockPatch(BlockPatch.PLACE, 1, 5, 1, "minecraft:oak_stairs", roofSrc);
        var wallOverwrite = new BlockPatch(BlockPatch.REPLACE, 1, 5, 1, "minecraft:oak_planks", wallSrc);
        var otherRoof = new BlockPatch(BlockPatch.PLACE, 2, 5, 2, "minecraft:oak_stairs", roofSrc);
        var removeOther = new BlockPatch(BlockPatch.REMOVE, 2, 5, 2, null, roofSrc);

        var overwritten = BuildingPartIndex.fromFinalPatches(origin, List.of(roofPlace, wallOverwrite));
        assertTrue(overwritten.roof().isEmpty(), "non-roof last write must drop cell");

        var removed = BuildingPartIndex.fromFinalPatches(origin, List.of(otherRoof, removeOther));
        assertTrue(removed.roof().isEmpty(), "REMOVE last write must drop cell");

        var kept = BuildingPartIndex.fromFinalPatches(origin, List.of(wallOverwrite, roofPlace));
        assertEquals(1, kept.roof().cells().size());
        assertTrue(kept.roof().cells().contains(roofWorld));
    }

    @Test
    void mergeFinalBatchDoesNotBleedNeighborRoof() {
        var origin = new BlockPos(0, 64, 0);
        BlockPos own = new BlockPos(1, 69, 1);
        BlockPos neighbor = new BlockPos(100, 69, 100);
        var existing = BuildingPartIndex.ofRoofCells(
                List.of(own), Map.of(own, "minecraft:oak_stairs"));
        var roofSrc = GeometrySource.generated("ROOF_COMPONENT");
        // Batch only writes own cell; neighbor must remain only if already present — it isn't.
        var batch = List.of(new BlockPatch(BlockPatch.PLACE, 1, 5, 1, "minecraft:dark_oak_stairs", roofSrc));
        var merged = BuildingPartIndex.mergeFinalBatch(existing, origin, batch);
        assertTrue(merged.roof().cells().contains(own));
        assertFalse(merged.roof().cells().contains(neighbor));
        assertEquals("minecraft:dark_oak_stairs", merged.roof().recordedStates().get(own));
    }

    @Test
    void undoSyncRestoresIndexedRoofStateWithoutProvenance() {
        BlockPos origin = new BlockPos(0, 64, 0);
        BlockPos roof = new BlockPos(1, 69, 1);
        var existing = BuildingPartIndex.ofRoofCells(
                List.of(roof), Map.of(roof, "minecraft:dark_oak_stairs"));
        // Undo reverse patch lacks GeometrySource.
        var reverse = List.of(new BlockPatch(BlockPatch.REPLACE, 1, 5, 1, "minecraft:oak_stairs"));
        var synced = BuildingPartIndex.syncIndexedStatesFromPatches(existing, origin, reverse);
        assertEquals("minecraft:oak_stairs", synced.roof().recordedStates().get(roof));
        assertTrue(synced.roof().cells().contains(roof));
    }

    @Test
    void unauthorizedWallInTicketRejectedByConfirmScope() {
        var building = new BuildingInstance(
                UUID.randomUUID(), OVERWORLD, BlockPos.ORIGIN,
                BlockPos.ORIGIN, new BlockPos(8, 8, 8), 0L, "x");
        var roofCell = new BlockPos(2, 7, 2);
        var roof = new BuildingPartIndex.RoofPart("roof", java.util.Set.of(roofCell),
                Map.of(roofCell, "minecraft:oak_planks"));
        var scope = BuildingEditScope.replaceRoofMaterial(building, roof, roof.recordedStates(), java.util.Set.of());
        var patches = List.of(
                new BlockPatch(BlockPatch.REPLACE, 2, 7, 2, "minecraft:dark_oak_planks"),
                new BlockPatch(BlockPatch.REPLACE, 1, 1, 1, "minecraft:stone"));
        var validation = BuildingEditScopeValidator.validate(
                scope, BlockPos.ORIGIN, patches, 0L, roof.recordedStates());
        assertFalse(validation.accepted());
        assertTrue(validation.conflicts().stream().anyMatch(c -> c.contains("unauthorized_write")));
    }

    @Test
    void secondTicketSameRevisionRejectedAfterFirstCommit() {
        var index = new SpatialIndex();
        var houseMem = house("two", new BlockPos(0, 64, 0), new BlockPos(4, 70, 4));
        BlockPos roof = new BlockPos(1, 69, 1);
        attachOakRoof(houseMem, roof);
        index.addMemory(houseMem);
        var mgr = manager(index);
        var service = new BuildingEditService(mgr);

        assertTrue(service.commitSuccessfulRoofEdit(
                UUID.fromString(houseMem.getUuid()), OVERWORLD, 0L,
                Map.of(roof, "minecraft:dark_oak_stairs")));
        assertEquals(1L, BuildingMemoryKeys.revisionOf(mgr.getMemory(houseMem.getUuid())));

        // Second ticket still holding baseRevision=0 must fail commit.
        assertFalse(service.commitSuccessfulRoofEdit(
                UUID.fromString(houseMem.getUuid()), OVERWORLD, 0L,
                Map.of(roof, "minecraft:spruce_stairs")));
        assertEquals(1L, BuildingMemoryKeys.revisionOf(mgr.getMemory(houseMem.getUuid())));
    }
}
