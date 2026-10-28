package com.formacraft.server.edit;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.memory.MemoryManager;
import com.formacraft.server.memory.MemoryUpdateResult;
import com.formacraft.server.memory.ProjectMemory;
import com.formacraft.server.memory.SpatialIndex;
import com.formacraft.server.preview.PreviewEditKind;
import com.formacraft.server.preview.PreviewTicketStorage;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round 64: window edit authority comes from resolved wall/window identity,
 * not from candidate patches.
 */
class BuildingAwareRound64Test {
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

    /** Small house: south façade with door at origin+2, opaque wall to the east (right when facing south). */
    private Map<BlockPos, BlockState> facadeLive(BlockPos origin) {
        Map<BlockPos, BlockState> live = new LinkedHashMap<>();
        for (int x = 0; x <= 8; x++) {
            for (int y = 0; y <= 5; y++) {
                for (int z = 0; z <= 6; z++) {
                    BlockPos p = origin.add(x, y, z);
                    boolean shell = x == 0 || x == 8 || z == 0 || z == 6 || y == 0 || y == 5;
                    live.put(p, shell ? Blocks.WHITE_TERRACOTTA.getDefaultState() : Blocks.AIR.getDefaultState());
                }
            }
        }
        // Doorway on south façade (z=0), facing south, at x=2
        BlockPos door = origin.add(2, 1, 0);
        live.put(door, Blocks.OAK_DOOR.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.SOUTH)
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        live.put(door.up(), Blocks.OAK_DOOR.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.SOUTH)
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        live.put(origin.add(2, 1, 0).offset(Direction.SOUTH), Blocks.AIR.getDefaultState());
        // Clear doorway walk space inside
        live.put(origin.add(2, 1, 1), Blocks.AIR.getDefaultState());
        live.put(origin.add(2, 2, 1), Blocks.AIR.getDefaultState());
        // Roof slab band at y=5 registered separately in index
        return live;
    }

    private void attachRoof(ProjectMemory memory, BlockPos origin) {
        List<BlockPos> cells = List.of(
                origin.add(1, 5, 1), origin.add(2, 5, 1), origin.add(3, 5, 1));
        Map<BlockPos, String> states = new HashMap<>();
        for (BlockPos c : cells) states.put(c, "minecraft:oak_stairs");
        BuildingEditService.attachRoofIndex(memory, BuildingPartIndex.ofRoofCells(cells, states));
    }

    @Test
    void resolveInsertAuthorizesWallEnvelopeIndependentOfPatches() {
        BlockPos origin = new BlockPos(0, 64, 0);
        var memory = house("ins", origin, origin.add(8, 5, 6));
        attachRoof(memory, origin);
        var building = BuildingInstance.fromMemory(memory);
        var live = facadeLive(origin);
        var index = BuildingPartIndex.fromMemory(memory);

        var resolved = WindowEditScopeResolver.resolveInsert(building, index, live, 2, 2);
        assertTrue(resolved.ok(), () -> resolved.refusalReason());
        assertFalse(resolved.authorizedCells().isEmpty());
        assertFalse(resolved.editCells().isEmpty());
        assertTrue(resolved.authorizedCells().containsAll(resolved.editCells()));
        assertTrue(resolved.authorizedCells().size() >= resolved.editCells().size());
        assertFalse(resolved.targetPartId().isBlank());
        assertFalse(resolved.facing().isBlank());

        // Crafted patches on the opposite (north) façade must be rejected even if opaque wall.
        BlockPos wrong = origin.add(4, 2, 6);
        var forged = List.of(new BlockPatch(
                BlockPatch.REPLACE,
                wrong.getX() - origin.getX(),
                wrong.getY() - origin.getY(),
                wrong.getZ() - origin.getZ(),
                "minecraft:glass"));
        var conflicts = WindowEditScopeResolver.validatePatchesAgainstScope(building, resolved, forged);
        assertFalse(conflicts.isEmpty());
        assertTrue(conflicts.stream().anyMatch(c -> c.contains("unauthorized_write")), () -> conflicts.toString());
    }

    @Test
    void prepareWindowInsertRejectsPillarAndRoofCells() {
        BlockPos origin = new BlockPos(10, 64, 10);
        var index = new SpatialIndex();
        var memory = house("prot", origin, origin.add(8, 5, 6));
        attachRoof(memory, origin);
        index.addMemory(memory);
        var service = new BuildingEditService(manager(index));
        var live = facadeLive(origin);

        // Place a pillar on a likely window cell to the right of the door.
        live.put(origin.add(4, 2, 0), Blocks.OAK_LOG.getDefaultState());

        var prepared = service.prepareWindowInsert(
                new BuildingTargetResolver.Query(OVERWORLD, UUID.fromString(memory.getUuid()),
                        null, null, origin.add(4, 64, 3), 32),
                live, 2, 2);
        // Either refuses rectangle or succeeds avoiding the pillar — never authorizes the pillar cell.
        if (prepared.ok()) {
            for (BlockPatch patch : prepared.patches()) {
                BlockPos world = origin.add(patch.dx(), patch.dy(), patch.dz());
                assertNotEquals(Blocks.OAK_LOG, live.get(world).getBlock());
                assertFalse(prepared.scope().allowedCells().contains(origin.add(1, 5, 1)));
            }
        } else {
            assertFalse(prepared.refusals().isEmpty());
        }
    }

    @Test
    void confirmGateRejectsPatchOutsideAuthorizedEnvelope() {
        BlockPos origin = new BlockPos(20, 64, 20);
        var index = new SpatialIndex();
        var memory = house("gate", origin, origin.add(8, 5, 6));
        attachRoof(memory, origin);
        index.addMemory(memory);
        var mgr = manager(index);
        var building = BuildingInstance.fromMemory(memory);
        var live = facadeLive(origin);
        var resolved = WindowEditScopeResolver.resolveInsert(
                building, BuildingPartIndex.fromMemory(memory), live, 2, 2);
        assertTrue(resolved.ok(), () -> resolved.refusalReason());

        UUID player = UUID.randomUUID();
        UUID ticket = UUID.randomUUID();
        var targetPart = BuildingEditTargetPart.wall(
                resolved.targetPartId(), resolved.floorIndex(), resolved.facing(),
                resolved.authorizedCells(), resolved.expectedWorldStates());
        var pending = new BuildingEditPendingRegistry.Pending(
                ticket, player, UUID.fromString(memory.getUuid()), OVERWORLD, 0L,
                BuildingEditOperation.INSERT_WINDOW,
                resolved.authorizedCells(),
                resolved.expectedWorldStates(),
                Map.of(resolved.editCells().iterator().next(), "minecraft:glass"),
                System.currentTimeMillis() + 60_000,
                targetPart);
        BuildingEditPendingRegistry.put(pending);

        BlockPos outsider = origin.add(1, 2, 6); // north wall, not in south envelope
        var forged = List.of(new BlockPatch(
                BlockPatch.REPLACE,
                outsider.getX() - origin.getX(),
                outsider.getY() - origin.getY(),
                outsider.getZ() - origin.getZ(),
                "minecraft:glass"));
        var outcome = BuildingEditConfirmGate.validateBeforeApply(
                mgr, null, player, OVERWORLD, origin, forged,
                PreviewEditKind.BUILDING_WINDOW_INSERT, pending);
        assertFalse(outcome.ok());
        assertTrue(outcome.message().contains("outside_authorized")
                        || outcome.message().contains("unauthorized"),
                () -> outcome.message());
    }

    @Test
    void neighborBuildingCellsCannotEnterAuthorizedScope() {
        BlockPos origin = new BlockPos(30, 64, 30);
        var memory = house("iso", origin, origin.add(8, 5, 6));
        attachRoof(memory, origin);
        var building = BuildingInstance.fromMemory(memory);
        var live = facadeLive(origin);
        BlockPos neighbor = origin.add(20, 2, 0);
        live.put(neighbor, Blocks.WHITE_TERRACOTTA.getDefaultState());

        var resolved = WindowEditScopeResolver.resolveInsert(
                building, BuildingPartIndex.fromMemory(memory), live, 2, 2);
        assertTrue(resolved.ok(), () -> resolved.refusalReason());
        assertFalse(resolved.authorizedCells().contains(neighbor));
        assertTrue(resolved.authorizedCells().stream().allMatch(building::contains));
    }

    @Test
    void windowCommitInvalidatesStaleRoofTicketRevision() {
        BuildingEditTransactionStore.bindDirectoryForTest(tmp.resolve("rev"));
        BlockPos origin = new BlockPos(40, 64, 40);
        var index = new SpatialIndex();
        var memory = house("rev", origin, origin.add(8, 5, 6));
        BlockPos roof = origin.add(2, 5, 2);
        BuildingEditService.attachRoofIndex(memory,
                BuildingPartIndex.ofRoofCells(List.of(roof), Map.of(roof, "minecraft:oak_stairs")));
        index.addMemory(memory);
        var service = new BuildingEditService(manager(index));
        UUID buildingId = UUID.fromString(memory.getUuid());

        // Stale roof pending at revision 0
        var roofPending = BuildingEditPendingRegistry.Pending.roof(
                UUID.randomUUID(), UUID.randomUUID(), buildingId, OVERWORLD, 0L,
                Set.of(roof), Map.of(roof, "minecraft:oak_stairs"),
                Map.of(roof, "minecraft:dark_oak_stairs"),
                System.currentTimeMillis() + 60_000);

        // Window edit advances shared revision
        BlockPos wall = origin.add(4, 66, 40);
        assertTrue(service.commitSuccessfulBuildingEdit(
                buildingId, OVERWORLD, 0L, BuildingEditOperation.INSERT_WINDOW,
                Map.of(wall, "minecraft:glass")));
        assertEquals(1L, BuildingMemoryKeys.revisionOf(index.getMemory(memory.getUuid())));

        var outcome = BuildingEditConfirmGate.validateBeforeApply(
                manager(index), null, roofPending.playerId(), OVERWORLD, origin,
                List.of(new BlockPatch(BlockPatch.REPLACE, 2, 5, 2, "minecraft:dark_oak_stairs")),
                PreviewEditKind.BUILDING_ROOF_MATERIAL, roofPending);
        assertFalse(outcome.ok());
        assertTrue(outcome.message().contains("revision_mismatch"), () -> outcome.message());
    }

    @Test
    void resolveSealRefusesAmbiguousWindowGroups() {
        BlockPos origin = new BlockPos(50, 64, 50);
        var memory = house("amb", origin, origin.add(10, 6, 6));
        var building = BuildingInstance.fromMemory(memory);
        Map<BlockPos, BlockState> live = new HashMap<>();
        // Two separate glass groups on a white wall
        for (int x = 0; x <= 10; x++) {
            for (int y = 0; y <= 5; y++) {
                live.put(origin.add(x, y, 0), Blocks.WHITE_TERRACOTTA.getDefaultState());
            }
        }
        for (int x = 1; x <= 2; x++) {
            for (int y = 1; y <= 2; y++) {
                live.put(origin.add(x, y, 0), Blocks.GLASS.getDefaultState());
            }
        }
        for (int x = 6; x <= 7; x++) {
            for (int y = 1; y <= 2; y++) {
                live.put(origin.add(x, y, 0), Blocks.GLASS.getDefaultState());
            }
        }
        var resolved = WindowEditScopeResolver.resolveSeal(
                building, BuildingPartIndex.fromMemory(memory), live, origin.add(5, 3, 3),
                p -> true, false, false);
        assertFalse(resolved.ok());
        assertEquals("WINDOW_GROUP_AMBIGUOUS", resolved.refusalReason());
    }

    @Test
    void wallAndWindowIdentityRecordsExistOnPartModel() {
        var face = new BuildingPartIndex.WallFace(
                "wall:south:f0", 0, "SOUTH",
                Set.of(new BlockPos(1, 2, 3)), Map.of(new BlockPos(1, 2, 3), "minecraft:stone"));
        var opening = new BuildingPartIndex.WindowOpening(
                "window:south:f0:1_2_3", "wall:south:f0", 0, "SOUTH",
                Set.of(new BlockPos(1, 2, 3)), Map.of(new BlockPos(1, 2, 3), "minecraft:glass"));
        var walls = new BuildingPartIndex.WallPart("wall", face.cells(), face.expectedStates(), List.of(face));
        var windows = new BuildingPartIndex.WindowPart(
                "window", opening.cells(), opening.expectedStates(), List.of(opening));
        assertEquals(1, walls.faces().size());
        assertEquals(1, windows.openings().size());
        assertEquals("wall:south:f0", windows.openings().getFirst().hostWallId());
        assertEquals(0, walls.faces().getFirst().floorIndex());
    }
}
