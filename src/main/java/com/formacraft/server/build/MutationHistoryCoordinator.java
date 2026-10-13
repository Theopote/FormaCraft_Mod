package com.formacraft.server.build;

import com.formacraft.common.patch.history.PatchReplayHistory;
import com.formacraft.common.patch.history.PatchTransaction;

import java.util.List;
import java.util.UUID;

/**
 * 协调「写入历史、登记账本、淘汰旧记录、清理 Redo」为一组操作。
 * <p>
 * 调用方应事先在 {@link PatchReplayHistory#setOnEvict} 上挂接底层附属结构清理；
 * 本类保证账本与淘汰 ID 同步，并在登记时 {@link PlayerMutationLedger#clearRedo}。
 */
public final class MutationHistoryCoordinator {
    private MutationHistoryCoordinator() {}

    public static UUID recordBuild(UUID playerId, PatchReplayHistory replay, Object world, PatchTransaction tx) {
        return record(playerId, PlayerMutationLedger.Kind.BUILD, replay, world, tx);
    }

    public static UUID recordPatch(UUID playerId, PatchReplayHistory replay, Object world, PatchTransaction tx) {
        return record(playerId, PlayerMutationLedger.Kind.PATCH, replay, world, tx);
    }

    private static UUID record(UUID playerId, PlayerMutationLedger.Kind kind,
                               PatchReplayHistory replay, Object world, PatchTransaction tx) {
        if (playerId == null || replay == null || tx == null || tx.before().isEmpty()) return null;
        UUID transactionId = UUID.randomUUID();
        PlayerMutationLedger ledger = PlayerMutationLedger.get();
        List<UUID> evicted = replay.record(world, tx, transactionId);
        for (UUID id : evicted) {
            ledger.remove(id);
        }
        ledger.record(transactionId, playerId, kind);
        return transactionId;
    }
}
