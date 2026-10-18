package com.formacraft.server.build;

import com.formacraft.common.patch.history.PatchReplayHistory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.UUID;

/**
 * 统一 Undo/Redo 入口：按 {@link PlayerMutationLedger} 时间序选择 BUILD 或 PATCH 栈执行一次。
 */
public final class MutationHistoryFacade {
    private MutationHistoryFacade() {}

    public static PatchReplayHistory.ReplayResult undo(ServerPlayerEntity player) {
        if (player == null) return PatchReplayHistory.ReplayResult.empty();
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return PatchReplayHistory.ReplayResult.empty();
        }
        return undo(player.getUuid(), world);
    }

    public static PatchReplayHistory.ReplayResult undo(UUID playerId, ServerWorld world) {
        if (playerId == null || world == null) return PatchReplayHistory.ReplayResult.empty();
        PlayerMutationLedger ledger = PlayerMutationLedger.get();
        // 丢弃与底层栈顶不一致的幽灵账本条目
        while (ledger.canUndo(playerId)) {
            PlayerMutationLedger.Entry peek = ledger.peekUndo(playerId);
            if (peek == null) break;
            UUID expected = peek.transactionId();
            UUID actual = switch (peek.kind()) {
                case BUILD -> BuildExecutionService.getInstance().getUndoService().peekUndoTransactionId(playerId);
                case PATCH -> PatchHistoryManager.peekUndoTransactionId(playerId);
            };
            if (expected.equals(actual)) break;
            com.formacraft.FormacraftMod.LOGGER.warn(
                    "Mutation ledger ghost undo entry {} ({}); dropping", expected, peek.kind());
            ledger.popUndo(playerId);
            ledger.remove(expected);
        }
        PlayerMutationLedger.Entry entry = ledger.popUndo(playerId);
        if (entry == null) return PatchReplayHistory.ReplayResult.empty();

        PatchReplayHistory.ReplayResult result = switch (entry.kind()) {
            case BUILD -> BuildExecutionService.getInstance().getUndoService()
                    .undo(playerId, world, com.formacraft.common.world.BlockMutationAccess.forWorld(world),
                            entry.transactionId());
            case PATCH -> PatchHistoryManager.undoDetailed(world, playerId);
        };

        if (!result.available()) {
            ledger.remove(entry.transactionId());
            return result;
        }
        if (result.wrongWorld() || !result.complete()) {
            ledger.restoreUndo(playerId, entry);
        }
        return result;
    }

    public static PatchReplayHistory.ReplayResult redo(ServerPlayerEntity player) {
        if (player == null) return PatchReplayHistory.ReplayResult.empty();
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return PatchReplayHistory.ReplayResult.empty();
        }
        return redo(player.getUuid(), world);
    }

    public static PatchReplayHistory.ReplayResult redo(UUID playerId, ServerWorld world) {
        if (playerId == null || world == null) return PatchReplayHistory.ReplayResult.empty();
        PlayerMutationLedger ledger = PlayerMutationLedger.get();
        while (ledger.canRedo(playerId)) {
            PlayerMutationLedger.Entry peek = ledger.peekRedo(playerId);
            if (peek == null) break;
            UUID expected = peek.transactionId();
            UUID actual = switch (peek.kind()) {
                case BUILD -> BuildExecutionService.getInstance().getUndoService().peekRedoTransactionId(playerId);
                case PATCH -> PatchHistoryManager.peekRedoTransactionId(playerId);
            };
            if (expected.equals(actual)) break;
            com.formacraft.FormacraftMod.LOGGER.warn(
                    "Mutation ledger ghost redo entry {} ({}); dropping", expected, peek.kind());
            ledger.popRedo(playerId);
            ledger.remove(expected);
        }
        PlayerMutationLedger.Entry entry = ledger.popRedo(playerId);
        if (entry == null) return PatchReplayHistory.ReplayResult.empty();

        PatchReplayHistory.ReplayResult result = switch (entry.kind()) {
            case BUILD -> BuildExecutionService.getInstance().getUndoService()
                    .redo(playerId, world, com.formacraft.common.world.BlockMutationAccess.forWorld(world),
                            entry.transactionId());
            case PATCH -> PatchHistoryManager.redoDetailed(world, playerId);
        };

        if (!result.available()) {
            ledger.remove(entry.transactionId());
            return result;
        }
        if (result.wrongWorld() || !result.complete()) {
            ledger.restoreRedo(playerId, entry);
        }
        return result;
    }

    public static boolean canUndo(UUID playerId) {
        return PlayerMutationLedger.get().canUndo(playerId);
    }

    public static boolean canRedo(UUID playerId) {
        PlayerMutationLedger ledger = PlayerMutationLedger.get();
        if (!ledger.canRedo(playerId)) return false;
        var kind = ledger.peekRedoKind(playerId);
        if (kind == PlayerMutationLedger.Kind.PATCH) {
            return PatchHistoryManager.canRedo(playerId);
        }
        if (kind == PlayerMutationLedger.Kind.BUILD) {
            return BuildExecutionService.getInstance().getUndoService().canRedo(playerId);
        }
        return false;
    }
}
