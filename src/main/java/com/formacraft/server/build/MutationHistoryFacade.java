package com.formacraft.server.build;

import com.formacraft.common.patch.history.PatchHistoryManager;
import com.formacraft.common.patch.history.PatchReplayHistory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.UUID;

/**
 * 统一 Undo/Redo 入口：按 {@link PlayerMutationLedger} 时间序选择 BUILD 或 PATCH 栈执行一次。
 * <p>
 * 通用命令与 Patch Undo/Redo 按钮均委托本 Facade，按账本时间序执行。
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
        PlayerMutationLedger.Entry entry = ledger.popUndo(playerId);
        if (entry == null) return PatchReplayHistory.ReplayResult.empty();

        PatchReplayHistory.ReplayResult result = switch (entry.kind()) {
            case BUILD -> BuildExecutionService.getInstance().getUndoService()
                    .undo(playerId, world, com.formacraft.common.world.BlockMutationAccess.forWorld(world));
            case PATCH -> PatchHistoryManager.undoDetailed(world, playerId);
        };

        if (!result.available() || result.wrongWorld() || !result.complete()) {
            // 未完成：保留在 undo，允许重试；从 redo 移回
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
        PlayerMutationLedger.Entry entry = ledger.popRedo(playerId);
        if (entry == null) return PatchReplayHistory.ReplayResult.empty();

        PatchReplayHistory.ReplayResult result;
        if (entry.kind() == PlayerMutationLedger.Kind.PATCH) {
            result = PatchHistoryManager.redoDetailed(world, playerId);
        } else {
            // BUILD 栈当前无独立 redo；把条目交还 redo 并报告不可用
            ledger.restoreRedo(playerId, entry);
            return PatchReplayHistory.ReplayResult.empty();
        }

        if (!result.available() || result.wrongWorld() || !result.complete()) {
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
        // BUILD 无 redo：若栈顶是 BUILD 则不可用
        // peek via temporary — use canRedo only when patch redo exists at top
        // Simpler: PatchHistoryManager.canRedo OR ledger has PATCH redo top
        var kind = peekRedoKind(playerId);
        return kind == PlayerMutationLedger.Kind.PATCH && PatchHistoryManager.canRedo(playerId);
    }

    private static PlayerMutationLedger.Kind peekRedoKind(UUID playerId) {
        // expose via temporary pop/push is racy; add peek on ledger
        return PlayerMutationLedger.get().peekRedoKind(playerId);
    }
}
