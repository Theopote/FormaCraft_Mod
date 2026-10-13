package com.formacraft.server.build;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.patch.history.PatchReplayHistory;
import com.formacraft.common.patch.history.PatchTransaction;
import com.formacraft.common.world.BlockSnapshot;
import com.formacraft.test.FakeBlockMutationAccess;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerMutationLedgerTest {
    private final UUID player = UUID.randomUUID();

    @BeforeAll
    static void init() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void tearDown() {
        PlayerMutationLedger.get().clearAll();
    }

    @Test
    void undoesInReverseChronologicalOrderAcrossKinds() {
        var ledger = PlayerMutationLedger.get();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        ledger.record(a, player, PlayerMutationLedger.Kind.BUILD);
        ledger.record(b, player, PlayerMutationLedger.Kind.PATCH);
        ledger.record(c, player, PlayerMutationLedger.Kind.BUILD);

        assertEquals(c, ledger.popUndo(player).transactionId());
        assertEquals(b, ledger.popUndo(player).transactionId());
        assertEquals(a, ledger.popUndo(player).transactionId());
        assertNull(ledger.popUndo(player));
    }

    @Test
    void incompleteRestoreReturnsEntryToUndoStack() {
        var ledger = PlayerMutationLedger.get();
        UUID id = UUID.randomUUID();
        ledger.record(id, player, PlayerMutationLedger.Kind.PATCH);
        var entry = ledger.popUndo(player);
        assertFalse(ledger.canUndo(player));
        assertTrue(ledger.canRedo(player));
        ledger.restoreUndo(player, entry);
        assertTrue(ledger.canUndo(player));
        assertEquals(PlayerMutationLedger.Kind.PATCH, ledger.peekUndoKind(player));
    }

    @Test
    void newRecordClearsRedoBranch() {
        var ledger = PlayerMutationLedger.get();
        ledger.record(UUID.randomUUID(), player, PlayerMutationLedger.Kind.BUILD);
        ledger.popUndo(player);
        assertTrue(ledger.canRedo(player));
        ledger.record(UUID.randomUUID(), player, PlayerMutationLedger.Kind.PATCH);
        assertFalse(ledger.canRedo(player));
        assertEquals(PlayerMutationLedger.Kind.PATCH, ledger.peekUndoKind(player));
    }

    @Test
    void buildHistoryEvictionRemovesLedgerEntries() {
        UndoService undo = new UndoService();
        Object world = new Object();
        var stone = Blocks.STONE.getDefaultState();
        for (int i = 0; i < 15; i++) {
            BlockPos p = new BlockPos(i, 1, 0);
            undo.record(player, world, BlockPos.ORIGIN, List.of(
                    new BlockChange(p, BlockSnapshot.of(Blocks.AIR.getDefaultState()),
                            BlockSnapshot.of(stone))));
        }
        assertEquals(10, undo.getUndoStackSize(player));
        assertTrue(PlayerMutationLedger.get().countKind(player, PlayerMutationLedger.Kind.BUILD) <= 10);
        assertEquals(10, PlayerMutationLedger.get().undoSize(player));
    }

    @Test
    void coordinatorEvictsKeepLedgerAlignedWithReplay() {
        PatchReplayHistory replay = new PatchReplayHistory(3);
        replay.setOnEvict(PlayerMutationLedger.get()::remove);
        Object world = new Object();
        for (int i = 0; i < 5; i++) {
            BlockPos p = new BlockPos(i, 1, 0);
            var tx = PatchTransaction.fromSnapshots(BlockPos.ORIGIN,
                    Map.of(p, BlockSnapshot.of(Blocks.AIR.getDefaultState())),
                    Map.of(p, BlockSnapshot.of(Blocks.STONE.getDefaultState())));
            MutationHistoryCoordinator.recordBuild(player, replay, world, tx);
        }
        assertEquals(3, replay.undoSize());
        assertEquals(3, PlayerMutationLedger.get().undoSize(player));
    }
}
