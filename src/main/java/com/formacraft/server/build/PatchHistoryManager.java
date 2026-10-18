package com.formacraft.server.build;

import com.formacraft.FormacraftMod;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BuildExecutionStatus;
import com.formacraft.common.patch.PatchExecutor;
import com.formacraft.common.patch.WorldPatchTransaction;
import com.formacraft.common.patch.history.PatchReplayHistory;
import com.formacraft.common.patch.history.PatchTransaction;
import com.formacraft.common.world.BlockMutationAccess;
import com.formacraft.common.world.BlockSnapshot;
import com.formacraft.server.memory.GeneMutation;
import com.formacraft.server.memory.MemoryManager;
import com.formacraft.server.memory.MutationBuilder;
import com.formacraft.server.memory.PatchDiffAnalyzer;
import com.formacraft.server.memory.PatchImpact;
import com.formacraft.server.memory.ProjectMemory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side patch undo/redo orchestration.
 * <p>
 * Replay stacks stay in {@link PatchReplayHistory}; ledger eviction, mutation
 * recording, and memory updates remain on the server layer.
 */
public final class PatchHistoryManager {
    private PatchHistoryManager() {}

    private static final int MAX_HISTORY = 50;

    private static final Map<UUID, PatchReplayHistory> PER_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, PatchExecutor.ApplyResult> LAST_APPLY_RESULT = new ConcurrentHashMap<>();
    private static final Map<UUID, WorldPatchTransaction.Result> LAST_TX_RESULT = new ConcurrentHashMap<>();

    private static PatchReplayHistory stacks(UUID playerId) {
        return PER_PLAYER.computeIfAbsent(playerId, k -> {
            PatchReplayHistory history = new PatchReplayHistory(MAX_HISTORY);
            history.setOnEvict(id -> PlayerMutationLedger.get().remove(id));
            return history;
        });
    }

    /** Visible for tests: capacity used when eviction fires. */
    static int maxHistory() {
        return MAX_HISTORY;
    }

    public static UUID peekUndoTransactionId(UUID playerId) {
        if (playerId == null) return null;
        PatchReplayHistory h = PER_PLAYER.get(playerId);
        return h == null ? null : h.peekUndoTransactionId();
    }

    public static UUID peekRedoTransactionId(UUID playerId) {
        if (playerId == null) return null;
        PatchReplayHistory h = PER_PLAYER.get(playerId);
        return h == null ? null : h.peekRedoTransactionId();
    }

    public static boolean canUndo(UUID playerId) {
        return playerId != null && stacks(playerId).canUndo();
    }

    public static boolean canRedo(UUID playerId) {
        return playerId != null && stacks(playerId).canRedo();
    }

    public static void clear(UUID playerId) {
        if (playerId == null) return;
        PER_PLAYER.remove(playerId);
        LAST_APPLY_RESULT.remove(playerId);
        LAST_TX_RESULT.remove(playerId);
        PlayerMutationLedger.get().clear(playerId);
    }

    public static void clearAll() {
        PER_PLAYER.clear();
        LAST_APPLY_RESULT.clear();
        LAST_TX_RESULT.clear();
        PlayerMutationLedger.get().clearAll();
    }

    /** 最近一次 apply 的统计（按玩家隔离）。 */
    public static PatchExecutor.ApplyResult getLastApplyResult(UUID playerId) {
        return playerId == null ? null : LAST_APPLY_RESULT.get(playerId);
    }

    /** 最近一次事务结果（含 BuildExecutionStatus）。 */
    public static WorldPatchTransaction.Result getLastTransactionResult(UUID playerId) {
        return playerId == null ? null : LAST_TX_RESULT.get(playerId);
    }

    /**
     * 应用 patch：WorldPatchTransaction 三阶段；仅 {@link BuildExecutionStatus#SUCCESS} 写入历史与 Memory。
     */
    public static WorldPatchTransaction.Result applyWithHistory(
            ServerWorld world, UUID playerId, BlockPos origin, List<BlockPatch> patches) {
        // Non-null contract: callers must never treat null as success.
        if (world == null || playerId == null
                || origin == null || patches == null || patches.isEmpty()) {
            return WorldPatchTransaction.Result.preflightRejected(null);
        }

        BlockMutationAccess access = BlockMutationAccess.forWorld(world);
        Set<BlockPos> affected = collectAffected(origin, patches);
        Map<BlockPos, BlockSnapshot> beforeForHistory = snapshot(access, affected);

        WorldPatchTransaction.Result txResult = WorldPatchTransaction.execute(access, origin, patches);
        LAST_TX_RESULT.put(playerId, txResult);
        if (txResult.apply() != null) {
            LAST_APPLY_RESULT.put(playerId, txResult.apply());
        }

        if (txResult.status() != BuildExecutionStatus.SUCCESS) {
            return txResult;
        }

        Map<BlockPos, BlockSnapshot> after = snapshot(access, affected);
        PatchTransaction tx = PatchTransaction.fromSnapshots(origin, beforeForHistory, after);
        if (tx.before().isEmpty()) return txResult;

        MutationHistoryCoordinator.recordPatch(playerId, stacks(playerId), world, tx);
        updateMemoryFromPatch(world, origin, tx.patches());
        return txResult;
    }

    /**
     * Record a completed patch transaction into this player's history without world apply.
     * Used by unit tests for eviction / isolation without a live ServerWorld.
     */
    static UUID recordCompletedTransaction(UUID playerId, Object world, PatchTransaction tx) {
        if (playerId == null || world == null || tx == null || tx.before().isEmpty()) return null;
        return MutationHistoryCoordinator.recordPatch(playerId, stacks(playerId), world, tx);
    }

    static int undoSize(UUID playerId) {
        if (playerId == null) return 0;
        PatchReplayHistory h = PER_PLAYER.get(playerId);
        return h == null ? 0 : h.undoSize();
    }

    /**
     * 从 Patch 更新 Memory（Memory → Patch → Memory 闭环的核心）
     */
    private static void updateMemoryFromPatch(ServerWorld world, BlockPos origin, List<BlockPatch> patches) {
        try {
            MemoryManager memoryManager =
                    BuildExecutionService.getInstance().getMemoryManager();

            if (memoryManager == null) {
                return;
            }

            PatchDiffAnalyzer analyzer = new PatchDiffAnalyzer(memoryManager);
            List<PatchImpact> impacts =
                    analyzer.analyze(world.getRegistryKey().getValue(), origin, patches);

            for (PatchImpact impact : impacts) {
                ProjectMemory memory = null;
                if (impact.getTargetBuilding() != null) {
                    memory = memoryManager.getMemory(impact.getTargetBuilding().toString());
                }

                GeneMutation mutation = MutationBuilder.build(impact, memory);

                if (mutation != null) {
                    memoryManager.applyMutation(
                            world.getRegistryKey().getValue(), mutation,
                            impact.getMinPos(),
                            impact.getMaxPos()
                    );
                }
            }
        } catch (Exception e) {
            FormacraftMod.LOGGER.warn("Failed to update memory from patch: {}", e.getMessage());
        }
    }

    /**
     * 撤销上一次事务。
     * @return 完整恢复的数量；-1 无历史或错误世界；-2 未完成。调用方应使用详细结果。
     */
    public static int undo(ServerWorld world, UUID playerId) {
        var result = undoDetailed(world, playerId);
        return result.complete() ? result.changed() : result.available() && !result.wrongWorld() ? -2 : -1;
    }

    public static int redo(ServerWorld world, UUID playerId) {
        var result = redoDetailed(world, playerId);
        return result.complete() ? result.changed() : result.available() && !result.wrongWorld() ? -2 : -1;
    }

    public static PatchReplayHistory.ReplayResult undoDetailed(ServerWorld world, UUID playerId) {
        if (world == null || playerId == null) return PatchReplayHistory.ReplayResult.empty();
        var result = stacks(playerId).undo(world, BlockMutationAccess.forWorld(world));
        if (result.delta() != null && !result.delta().patches().isEmpty()) {
            updateMemoryFromPatchUndo(world, result.delta().origin(), result.delta().patches());
        }
        return result;
    }

    public static PatchReplayHistory.ReplayResult redoDetailed(ServerWorld world, UUID playerId) {
        if (world == null || playerId == null) return PatchReplayHistory.ReplayResult.empty();
        var result = stacks(playerId).redo(world, BlockMutationAccess.forWorld(world));
        if (result.delta() != null && !result.delta().patches().isEmpty()) {
            updateMemoryFromPatch(world, result.delta().origin(), result.delta().patches());
        }
        return result;
    }

    /**
     * Undo 时的 Memory 更新（反向 Mutation）
     */
    private static void updateMemoryFromPatchUndo(ServerWorld world, BlockPos origin, List<BlockPatch> reversePatches) {
        try {
            MemoryManager memoryManager =
                    BuildExecutionService.getInstance().getMemoryManager();

            if (memoryManager == null) {
                return;
            }

            PatchDiffAnalyzer analyzer = new PatchDiffAnalyzer(memoryManager);

            List<PatchImpact> impacts =
                    analyzer.analyze(world.getRegistryKey().getValue(), origin, reversePatches);

            for (PatchImpact impact : impacts) {
                ProjectMemory memory = null;
                if (impact.getTargetBuilding() != null) {
                    memory = memoryManager.getMemory(impact.getTargetBuilding().toString());
                }

                GeneMutation mutation = MutationBuilder.build(impact, memory);

                if (mutation != null) {
                    Map<String, Object> delta = new HashMap<>(mutation.geneDelta());
                    delta.put("undone", true);
                    delta.put("undo_timestamp", System.currentTimeMillis());

                    GeneMutation undoMutation = new GeneMutation(
                            mutation.buildingId(),
                            "Undo: " + mutation.reason(),
                            mutation.affectedParts(),
                            delta
                    );

                    memoryManager.applyMutation(
                            world.getRegistryKey().getValue(), undoMutation,
                            impact.getMinPos(), impact.getMaxPos());
                }
            }
        } catch (Exception e) {
            FormacraftMod.LOGGER.warn("Failed to update memory from patch undo: {}", e.getMessage());
        }
    }

    private static Set<BlockPos> collectAffected(BlockPos origin, List<BlockPatch> patches) {
        Set<BlockPos> set = new HashSet<>(patches.size());
        for (BlockPatch p : patches) {
            if (p == null) continue;
            set.add(origin.add(p.dx(), p.dy(), p.dz()).toImmutable());
        }
        return set;
    }

    private static Map<BlockPos, BlockSnapshot> snapshot(BlockMutationAccess access, Set<BlockPos> positions) {
        Map<BlockPos, BlockSnapshot> map = new HashMap<>(positions.size());
        for (BlockPos pos : positions) {
            if (access.isInsideHeight(pos) && access.isChunkReady(pos)) {
                map.put(pos, access.getSnapshot(pos));
            }
        }
        return map;
    }
}
