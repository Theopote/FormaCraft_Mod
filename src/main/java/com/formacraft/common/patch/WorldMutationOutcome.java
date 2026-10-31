package com.formacraft.common.patch;

import java.util.UUID;

/**
 * Shared terminal outcome for Build (tick) and Patch (sync) mutation strategies.
 * Strategies may differ in scheduling; success / history / rollback semantics share this model.
 */
public record WorldMutationOutcome(
        BuildExecutionStatus status,
        Strategy strategy,
        UUID transactionId,
        int appliedCells,
        int recoveredFull,
        int recoveredStateOnly,
        int recoveryFailed
) {
    public enum Strategy {
        /** {@code BuildTask} multi-tick placement. */
        BUILD_TICKED,
        /** {@code WorldPatchTransaction} synchronous confirm. */
        PATCH_SYNC
    }

    public boolean recordsHistory() {
        return status != null && status.recordsHistory();
    }

    public static WorldMutationOutcome patch(WorldPatchTransaction.Result result, UUID transactionId) {
        if (result == null) {
            return new WorldMutationOutcome(BuildExecutionStatus.PREFLIGHT_REJECTED, Strategy.PATCH_SYNC,
                    transactionId, 0, 0, 0, 0);
        }
        return new WorldMutationOutcome(
                result.status(),
                Strategy.PATCH_SYNC,
                transactionId,
                result.applied(),
                result.recoveredFull(),
                result.recoveredStateOnly(),
                result.recoveryFailed()
        );
    }

    public static WorldMutationOutcome build(
            BuildExecutionStatus status,
            int appliedCells,
            int recoveredFull,
            int recoveredStateOnly,
            int recoveryFailed
    ) {
        return new WorldMutationOutcome(
                status, Strategy.BUILD_TICKED, null,
                appliedCells, recoveredFull, recoveredStateOnly, recoveryFailed
        );
    }
}
