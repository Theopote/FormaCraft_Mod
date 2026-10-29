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
 * Round 65: targetPartId binding, FloorBand targeting, Confirm identity, Undo identity.
 */
class BuildingAwareRound65Test {
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

    private Map<BlockPos, BlockState> twoStoreyFacade(BlockPos origin) {
        Map<BlockPos, BlockState> live = new LinkedHashMap<>();
        // Two solid floor plates at y=0 and y=4, walls to y=7, roof plate y=8.
        for (int x = 0; x <= 8; x++) {
            for (int y = 0; y <= 8; y++) {
                for (int z = 0; z <= 6; z++) {
                    BlockPos p = origin.add(x, y, z);
                    boolean plate = y == 0 || y == 4 || y == 8;
                    boolean shell = x == 0 || x == 8 || z == 0 || z == 6;
                    live.put(p, (plate || shell) && y <= 8
                            ? Blocks.WHITE_TERRACOTTA.getDefaultState()
                            : Blocks.AIR.getDefaultState());
                }
            }
        }
        // Ground entrance on south façade
        BlockPos door = origin.add(2, 1, 0);
        live.put(door, Blocks.OAK_DOOR.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.SOUTH)
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        live.put(door.up(), Blocks.OAK_DOOR.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.SOUTH)
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        live.put(origin.add(2, 1, 1), Blocks.AIR.getDefaultState());
        live.put(origin.add(2, 2, 1), Blocks.AIR.getDefaultState());
        // Second-storey east façade solid (for targeting refusal when asking east floor1 without entrance)
        return live;
    }

    private void attachRoof(ProjectMemory memory, BlockPos origin) {
        List<BlockPos> cells = List.of(origin.add(1, 8, 1), origin.add(2, 8, 1), origin.add(3, 8, 1));
        Map<BlockPos, String> states = new HashMap<>();
        for (BlockPos c : cells) states.put(c, "minecraft:oak_stairs");
        BuildingEditService.attachRoofIndex(memory, BuildingPartIndex.ofRoofCells(cells, states));
    }

    @Test
    void floorBandsUseLivePlatesNotFixedFour() {
        BlockPos origin = new BlockPos(0, 64, 0);
        var memory = house("bands", origin, origin.add(8, 8, 6));
        var building = BuildingInstance.fromMemory(memory);
        var live = twoStoreyFacade(origin);
        var bands = BuildingFloorBands.fromLive(building, live);
        assertTrue(bands.size() >= 2, () -> "bands=" + bands);
        assertEquals(0, bands.getFirst().floorIndex());
        assertTrue(bands.getFirst().containsY(origin.getY() + 1), () -> "bands=" + bands);
        assertFalse(bands.getFirst().containsY(origin.getY() + 5), () -> "bands=" + bands);
        assertEquals(1, BuildingFloorBands.floorIndexOf(bands, origin.getY() + 5), () -> "bands=" + bands);
    }

    @Test
    void explicitSecondFloorEastRefusesGroundSouthFallback() {
        BlockPos origin = new BlockPos(10, 64, 10);
        var memory = house("target", origin, origin.add(8, 8, 6));
        attachRoof(memory, origin);
        var building = BuildingInstance.fromMemory(memory);
        var live = twoStoreyFacade(origin);
        var resolved = WindowEditScopeResolver.resolveInsert(
                building, BuildingPartIndex.fromMemory(memory), live, 2, 2,
                new WindowEditScopeResolver.InsertTarget(1, "EAST"));
        assertFalse(resolved.ok());
        assertTrue(Set.of("WALL_TARGET_UNMATCHED", "WALL_FLOOR_MISMATCH", "WALL_FACING_MISMATCH", "ENTRANCE_NOT_FOUND")
                .contains(resolved.refusalReason()), () -> resolved.refusalReason());
    }

    @Test
    void prepareBindsTargetPartIntoPendingAndTxnSurvivesRestartShape() {
        BuildingEditTransactionStore.bindDirectoryForTest(tmp.resolve("txn-part"));
        BlockPos origin = new BlockPos(20, 64, 20);
        var index = new SpatialIndex();
        var memory = house("bind", origin, origin.add(8, 5, 6));
        attachRoof(memory, origin);
        // Single-storey façade matching Round64 layout
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
        BlockPos door = origin.add(2, 1, 0);
        live.put(door, Blocks.OAK_DOOR.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.SOUTH)
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        live.put(door.up(), Blocks.OAK_DOOR.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.SOUTH)
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        live.put(origin.add(2, 1, 1), Blocks.AIR.getDefaultState());
        live.put(origin.add(2, 2, 1), Blocks.AIR.getDefaultState());
        index.addMemory(memory);
        var service = new BuildingEditService(manager(index));
        var prepared = service.prepareWindowInsert(
                new BuildingTargetResolver.Query(
                        OVERWORLD, UUID.fromString(memory.getUuid()), null, null, null, 16),
                live, 2, 2);
        assertTrue(prepared.ok(), () -> prepared.refusals().toString());
        assertTrue(prepared.scope().targetPart().isPresent());
        assertFalse(prepared.scope().targetPart().targetPartId().isBlank());

        UUID ticket = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        BuildingEditService.bindPendingToTicket(ticket, player, System.currentTimeMillis() + 60_000, prepared);
        var pending = BuildingEditPendingRegistry.get(ticket);
        assertNotNull(pending);
        assertEquals(prepared.scope().targetPart().identityDigest(), pending.targetPart().identityDigest());

        var begin = service.beginTransaction(pending);
        assertTrue(begin.ok(), () -> begin.message());
        assertEquals(pending.targetPart().targetPartId(), begin.transaction().targetPart().targetPartId());

        var roundTrip = BuildingEditTransaction.fromMap(begin.transaction().toMap());
        assertNotNull(roundTrip);
        assertEquals(begin.transaction().targetPart().identityDigest(), roundTrip.targetPart().identityDigest());
    }

    @Test
    void confirmRejectsWhenSameCellsOwnedByDifferentWindowIdentity() {
        BlockPos origin = new BlockPos(30, 64, 30);
        var index = new SpatialIndex();
        var memory = house("replace", origin, origin.add(8, 5, 6));
        attachRoof(memory, origin);
        BlockPos cell = origin.add(4, 2, 0);
        var foreign = new BuildingPartIndex.WindowOpening(
                "window:south:f0:foreign", "wall:south:f0", 0, "SOUTH",
                Set.of(cell), Map.of(cell, "minecraft:glass"));
        new BuildingPartIndex(
                BuildingPartIndex.fromMemory(memory).roof(),
                BuildingPartIndex.fromMemory(memory).walls(),
                new BuildingPartIndex.WindowPart("window", Set.of(cell), Map.of(cell, "minecraft:glass"), List.of(foreign))
        ).persistTo(memory);
        index.addMemory(memory);

        var part = BuildingEditTargetPart.window(
                "window:south:f0:legacy", "wall:south:f0", 0, "SOUTH",
                Set.of(cell), Map.of(cell, "minecraft:glass"));
        var pending = new BuildingEditPendingRegistry.Pending(
                UUID.randomUUID(), UUID.randomUUID(), UUID.fromString(memory.getUuid()), OVERWORLD, 0L,
                BuildingEditOperation.SEAL_WINDOW,
                Set.of(cell), Map.of(cell, "minecraft:glass"), Map.of(cell, "minecraft:white_terracotta"),
                System.currentTimeMillis() + 60_000, part);

        var outcome = BuildingEditConfirmGate.validateBeforeApply(
                manager(index), null, pending.playerId(), OVERWORLD, origin,
                List.of(new BlockPatch(BlockPatch.REPLACE, 4, 2, 0, "minecraft:white_terracotta")),
                PreviewEditKind.BUILDING_WINDOW_SEAL, pending);
        assertFalse(outcome.ok());
        assertTrue(outcome.message().contains("identity_replaced")
                        || outcome.message().contains("target_part"),
                () -> outcome.message());
    }

    @Test
    void insertCommitRecordsWindowOpeningAndUndoSyncDropsIt() {
        var index = new SpatialIndex();
        var house = house("undo-id", new BlockPos(0, 64, 0), new BlockPos(8, 70, 8));
        BlockPos roof = new BlockPos(2, 69, 2);
        BlockPos wall = new BlockPos(4, 66, 0);
        BuildingEditService.attachRoofIndex(house,
                BuildingPartIndex.ofRoofCells(List.of(roof), Map.of(roof, "minecraft:oak_stairs")));
        index.addMemory(house);
        var service = new BuildingEditService(manager(index));
        UUID buildingId = UUID.fromString(house.getUuid());

        var target = BuildingEditTargetPart.wall(
                "wall:south:f0:z0", 0, "SOUTH",
                Set.of(wall), Map.of(wall, "minecraft:white_terracotta"));
        assertTrue(service.commitSuccessfulBuildingEdit(
                buildingId, OVERWORLD, 0L, BuildingEditOperation.INSERT_WINDOW,
                Map.of(wall, "minecraft:glass"), target));

        ProjectMemory after = index.getMemory(house.getUuid());
        var partIndex = BuildingPartIndex.fromMemory(after);
        assertEquals(1, partIndex.windows().openings().size());
        assertEquals("wall:south:f0:z0", partIndex.windows().openings().getFirst().hostWallId());
        assertEquals(1L, BuildingMemoryKeys.revisionOf(after));

        var synced = BuildingPartIndex.syncIndexedStatesFromPatches(
                partIndex, BlockPos.ORIGIN,
                List.of(new BlockPatch(BlockPatch.REPLACE, 4, 66, 0, "minecraft:white_terracotta")));
        assertTrue(synced.windows().openings().isEmpty());
        assertTrue(synced.walls().cells().contains(wall));
        assertFalse(synced.windows().cells().contains(wall));
    }

    @Test
    void insertAcceptsWallRectangleWhenTrailingJambIsExistingGlass() {
        // In-game: solid 2×2 to the right of the door, then an already-punched façade window.
        // Old chooseInsertRectangle required solid(distance+width) and refused WALL_RECTANGLE_UNAVAILABLE.
        BlockPos origin = new BlockPos(50, 64, 50);
        var memory = house("jamb-glass", origin, origin.add(8, 5, 6));
        attachRoof(memory, origin);
        Map<BlockPos, BlockState> live = new LinkedHashMap<>();
        for (int x = 0; x <= 8; x++) {
            for (int y = 0; y <= 5; y++) {
                for (int z = 0; z <= 6; z++) {
                    BlockPos p = origin.add(x, y, z);
                    boolean shell = x == 0 || x == 8 || z == 0 || z == 6 || y == 0 || y == 5;
                    live.put(p, shell ? Blocks.OAK_PLANKS.getDefaultState() : Blocks.AIR.getDefaultState());
                }
            }
        }
        BlockPos door = origin.add(2, 1, 0);
        live.put(door, Blocks.OAK_DOOR.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.SOUTH)
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        live.put(door.up(), Blocks.OAK_DOOR.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.SOUTH)
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        live.put(origin.add(2, 1, 1), Blocks.AIR.getDefaultState());
        live.put(origin.add(2, 2, 1), Blocks.AIR.getDefaultState());
        // Existing window immediately beyond a 2-wide solid strip (lat 4 from door edge at x=2 → x=6).
        live.put(origin.add(6, 1, 0), Blocks.GLASS.getDefaultState());
        live.put(origin.add(6, 2, 0), Blocks.GLASS.getDefaultState());
        live.put(origin.add(7, 1, 0), Blocks.GLASS.getDefaultState());
        live.put(origin.add(7, 2, 0), Blocks.GLASS.getDefaultState());

        var resolved = WindowEditScopeResolver.resolveInsert(
                BuildingInstance.fromMemory(memory), BuildingPartIndex.fromMemory(memory), live, 2, 2);
        assertTrue(resolved.ok(), () -> resolved.refusalReason());
        assertEquals(4, resolved.editCells().size());
        assertTrue(resolved.editCells().stream().noneMatch(c -> live.get(c).isOf(Blocks.GLASS)));
    }
}
