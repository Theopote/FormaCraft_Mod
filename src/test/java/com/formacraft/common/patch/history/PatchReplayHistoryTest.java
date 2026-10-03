package com.formacraft.common.patch.history;

import com.formacraft.test.FakeBlockMutationAccess;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PatchReplayHistoryTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }
    private static final BlockPos A = new BlockPos(0, 1, 0), B = new BlockPos(1, 1, 0);
    private PatchTransaction transaction(BlockPos... positions) {
        Map<BlockPos, BlockState> before = new HashMap<>(), after = new HashMap<>();
        for (var p : positions) { before.put(p, Blocks.AIR.getDefaultState()); after.put(p, Blocks.STONE.getDefaultState()); }
        return PatchTransaction.fromSnapshots(BlockPos.ORIGIN, before, after);
    }
    private FakeBlockMutationAccess access(BlockPos... positions) {
        var access = new FakeBlockMutationAccess();
        for (var p : positions) access.states.put(p, Blocks.STONE.getDefaultState());
        return access;
    }
    @Test void failedUndoRemainsRetryableAndOnlyCompletedTransactionMovesStacks() {
        Object world = new Object(); var history = new PatchReplayHistory(50); var access = access(A, B);
        history.record(world, transaction(A, B)); access.rejected.add(B);
        var partial = history.undo(world, access);
        assertEquals(1, partial.changed()); assertEquals(1, partial.failedWrites()); assertEquals(1, partial.remaining());
        assertEquals(1, partial.delta().patches().size()); assertTrue(history.canUndo()); assertFalse(history.canRedo());
        assertTrue(partial.summaryZh("撤销").contains("可重试"));
        access.rejected.clear(); var retry = history.undo(world, access);
        assertEquals(1, retry.changed()); assertTrue(retry.complete()); assertEquals(3, access.writes);
        assertFalse(history.canUndo()); assertTrue(history.canRedo());
        assertEquals(2, history.redo(world, access).changed());
    }
    @Test void failedRedoKeepsSourceStackAndBlocksOppositeReplayUntilRetryCompletes() {
        Object world = new Object(); var history = new PatchReplayHistory(50); var access = access(A, B);
        history.record(world, transaction(A, B)); assertTrue(history.undo(world, access).complete());
        access.rejected.add(B); var partial = history.redo(world, access);
        assertEquals(1, partial.changed()); assertFalse(partial.complete()); assertTrue(history.canRedo());
        assertFalse(history.canUndo()); assertFalse(history.undo(world, access).available());
        access.rejected.clear(); assertTrue(history.redo(world, access).complete()); assertTrue(history.canUndo());
    }
    @Test void wrongWorldAndNewServerIdentityNeverWriteOrLoseHistory() {
        Object world = new Object(); var history = new PatchReplayHistory(50); var access = access(A);
        history.record(world, transaction(A));
        assertTrue(history.undo(new Object(), access).wrongWorld()); assertEquals(0, access.writes);
        assertTrue(history.canUndo()); assertTrue(history.undo(world, access).complete());
        assertTrue(history.redo(new Object(), access).wrongWorld()); assertTrue(history.canRedo());
    }
    @Test void laterEditsAreConflictsRatherThanOverwritten() {
        Object world = new Object(); var history = new PatchReplayHistory(50); var access = access(A);
        history.record(world, transaction(A)); access.states.put(A, Blocks.OAK_PLANKS.getDefaultState());
        var conflict = history.undo(world, access);
        assertEquals(1, conflict.conflicts()); assertEquals(0, access.writes); assertTrue(history.canUndo());
        access.states.put(A, Blocks.STONE.getDefaultState()); assertTrue(history.undo(world, access).complete());
    }
    @Test void alreadyTargetNeedsNoWriteAndEmptyRecordKeepsRedo() {
        Object world = new Object(); var history = new PatchReplayHistory(50); var access = access(A);
        history.record(world, transaction(A)); access.states.put(A, Blocks.AIR.getDefaultState());
        var result = history.undo(world, access);
        assertTrue(result.complete()); assertEquals(1, result.alreadyCorrect()); assertEquals(0, result.changed());
        assertTrue(result.delta().patches().isEmpty());
        history.record(world, PatchTransaction.fromSnapshots(BlockPos.ORIGIN, Map.of(), Map.of()));
        assertTrue(history.canRedo());
    }
    @Test void newBranchPreservesCompletedPartOfPartialRedoInUndoHistory() {
        Object world = new Object(); var history = new PatchReplayHistory(50); var access = access(A, B);
        history.record(world, transaction(A, B)); history.undo(world, access);
        access.rejected.add(B); history.redo(world, access);
        BlockPos c = new BlockPos(2, 1, 0); access.states.put(c, Blocks.STONE.getDefaultState());
        history.record(world, transaction(c)); assertFalse(history.canRedo());
        assertTrue(history.undo(world, access).complete()); assertTrue(history.undo(world, access).complete());
        assertTrue(access.states.get(A).isAir()); assertTrue(access.states.get(B).isAir()); assertTrue(access.states.get(c).isAir());
    }
    @Test void blockedLocationsAreRetainedWithoutUnsafeWorldReads() {
        Object world = new Object(); var history = new PatchReplayHistory(50); var access = access();
        history.record(world, transaction(new BlockPos(0, -1, 0), new BlockPos(16, 1, 0)));
        var result = history.undo(world, access);
        assertEquals(2, result.blocked()); assertEquals(2, result.remaining()); assertEquals(0, access.writes);
    }
}
