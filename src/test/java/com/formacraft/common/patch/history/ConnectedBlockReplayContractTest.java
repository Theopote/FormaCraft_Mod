package com.formacraft.common.patch.history;

import com.formacraft.common.world.BlockSnapshot;
import com.formacraft.test.FakeBlockMutationAccess;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 contract lock: pane soft-match must not silently expand to fences/walls.
 * Neighbor-derived connection differences on fences/walls remain undo conflicts.
 */
class ConnectedBlockReplayContractTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void fenceAndWallConnectionDriftIsNotSoftMatched() {
        BlockState fence = Blocks.OAK_FENCE.getDefaultState();
        BlockState fenceEast = fence.with(Properties.EAST, true);
        assertTrue(fence.getBlock() instanceof FenceBlock);
        assertFalse(ReplaySnapshotMatcher.matches(
                BlockSnapshot.of(fenceEast), BlockSnapshot.of(fence)));

        BlockState wall = Blocks.STONE_BRICK_WALL.getDefaultState();
        assertTrue(wall.getBlock() instanceof WallBlock);
        // Walls use enum connection shapes; any non-equal state must stay strict.
        BlockState wallNorth = wall.with(Properties.NORTH_WALL_SHAPE,
                net.minecraft.block.enums.WallShape.LOW);
        assertNotEquals(wall, wallNorth);
        assertFalse(ReplaySnapshotMatcher.matches(
                BlockSnapshot.of(wallNorth), BlockSnapshot.of(wall)));
    }

    @Test
    void fenceConnectionOnlyChangeRemainsUndoConflict() {
        FakeBlockMutationAccess access = new FakeBlockMutationAccess();
        BlockPos pos = new BlockPos(4, 2, 4);
        BlockState fence = Blocks.OAK_FENCE.getDefaultState();
        access.states.put(pos, fence.with(Properties.NORTH, true));

        PatchReplayHistory history = new PatchReplayHistory(10);
        Object world = new Object();
        history.record(world, PatchTransaction.fromStateSnapshots(
                BlockPos.ORIGIN,
                Map.of(pos, Blocks.AIR.getDefaultState()),
                Map.of(pos, fence)));

        PatchReplayHistory.ReplayResult result = history.undo(world, access);
        assertEquals(1, result.conflicts());
        assertEquals(0, access.writes);
        assertFalse(result.complete());
    }

    @Test
    void paneSoftMatchStillWorksAlongsideFenceStrictness() {
        BlockState pane = Blocks.GLASS_PANE.getDefaultState();
        BlockState paneConnected = pane
                .with(Properties.NORTH, true)
                .with(Properties.EAST, true);
        assertTrue(ReplaySnapshotMatcher.matches(
                BlockSnapshot.of(paneConnected), BlockSnapshot.of(pane)));
        assertFalse(ReplaySnapshotMatcher.matches(
                BlockSnapshot.of(paneConnected.with(Properties.WATERLOGGED, true)),
                BlockSnapshot.of(pane)));
    }
}
