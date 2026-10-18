package com.formacraft.server.build;

import com.formacraft.common.patch.history.PatchTransaction;
import com.formacraft.common.world.BlockSnapshot;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PatchHistoryManagerTest {
    private final UUID playerA = UUID.randomUUID();
    private final UUID playerB = UUID.randomUUID();

    @BeforeAll
    static void init() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void tearDown() {
        PatchHistoryManager.clearAll();
    }

    @Test
    void evictionRemovesLedgerEntriesForOldestTransactions() {
        Object world = new Object();
        int capacity = PatchHistoryManager.maxHistory();
        UUID first = null;
        for (int i = 0; i < capacity + 3; i++) {
            BlockPos p = new BlockPos(i, 1, 0);
            UUID id = PatchHistoryManager.recordCompletedTransaction(playerA, world, txAt(p));
            if (i == 0) first = id;
        }
        assertEquals(capacity, PatchHistoryManager.undoSize(playerA));
        assertEquals(capacity, PlayerMutationLedger.get().undoSize(playerA));

        java.util.Set<UUID> remaining = new java.util.HashSet<>();
        while (PlayerMutationLedger.get().canUndo(playerA)) {
            remaining.add(PlayerMutationLedger.get().popUndo(playerA).transactionId());
        }
        assertFalse(remaining.contains(first));
        assertEquals(capacity, remaining.size());
    }

    @Test
    void historiesAreIsolatedAcrossPlayers() {
        Object world = new Object();
        UUID aTx = PatchHistoryManager.recordCompletedTransaction(
                playerA, world, txAt(new BlockPos(1, 1, 0)));
        UUID bTx = PatchHistoryManager.recordCompletedTransaction(
                playerB, world, txAt(new BlockPos(2, 1, 0)));

        assertEquals(1, PatchHistoryManager.undoSize(playerA));
        assertEquals(1, PatchHistoryManager.undoSize(playerB));
        assertEquals(aTx, PatchHistoryManager.peekUndoTransactionId(playerA));
        assertEquals(bTx, PatchHistoryManager.peekUndoTransactionId(playerB));
        assertNotEquals(aTx, bTx);

        PatchHistoryManager.clear(playerA);
        assertEquals(0, PatchHistoryManager.undoSize(playerA));
        assertFalse(PlayerMutationLedger.get().canUndo(playerA));
        assertEquals(1, PatchHistoryManager.undoSize(playerB));
        assertEquals(bTx, PatchHistoryManager.peekUndoTransactionId(playerB));
        assertTrue(PlayerMutationLedger.get().canUndo(playerB));
    }

    private static PatchTransaction txAt(BlockPos p) {
        return PatchTransaction.fromSnapshots(
                BlockPos.ORIGIN,
                Map.of(p, BlockSnapshot.of(Blocks.AIR.getDefaultState())),
                Map.of(p, BlockSnapshot.of(Blocks.STONE.getDefaultState())));
    }
}
