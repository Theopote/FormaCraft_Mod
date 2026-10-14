package com.formacraft.common.patch.history;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BuildExecutionStatus;
import com.formacraft.common.patch.PatchExecutor;
import com.formacraft.common.patch.WorldPatchTransaction;
import com.formacraft.common.world.BlockMutationAccess;
import com.formacraft.common.world.BlockSnapshot;
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
 * Patch Undo/Redo 历史栈（服务端逻辑）。
 *
 * - 以玩家 UUID 维度隔离历史，避免多人互相干扰
 * - 以 Transaction 记录 before/after，保证可逆
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
            history.setOnEvict(id -> com.formacraft.server.build.PlayerMutationLedger.get().remove(id));
            return history;
        });
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
    public static boolean canUndo(UUID playerId) { return playerId != null && stacks(playerId).canUndo(); }
    public static boolean canRedo(UUID playerId) { return playerId != null && stacks(playerId).canRedo(); }
    public static void clear(UUID playerId) {
        if (playerId == null) return;
        PER_PLAYER.remove(playerId);
        LAST_APPLY_RESULT.remove(playerId);
        LAST_TX_RESULT.remove(playerId);
    }
    public static void clearAll() {
        PER_PLAYER.clear();
        LAST_APPLY_RESULT.clear();
        LAST_TX_RESULT.clear();
        com.formacraft.server.build.PlayerMutationLedger.get().clearAll();
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
        if (world == null || playerId == null) return null;
        if (origin == null || patches == null || patches.isEmpty()) return null;

        BlockMutationAccess access = BlockMutationAccess.forWorld(world);
        Set<BlockPos> affected = collectAffected(origin, patches);
        // Capture before for history only after SUCCESS (transaction already snapshotted internally).
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

        com.formacraft.server.build.MutationHistoryCoordinator.recordPatch(
                playerId, stacks(playerId), world, tx);
        updateMemoryFromPatch(world, origin, tx.patches());
        return txResult;
    }
    
    /**
     * 从 Patch 更新 Memory（Memory → Patch → Memory 闭环的核心）
     */
    private static void updateMemoryFromPatch(ServerWorld world, BlockPos origin, List<BlockPatch> patches) {
        try {
            // 获取 MemoryManager
            com.formacraft.server.memory.MemoryManager memoryManager = 
                com.formacraft.server.build.BuildExecutionService.getInstance().getMemoryManager();
            
            if (memoryManager == null) {
                // Memory 系统未初始化，跳过
                return;
            }
            
            // 分析 Patch 影响
            com.formacraft.server.memory.PatchDiffAnalyzer analyzer = 
                new com.formacraft.server.memory.PatchDiffAnalyzer(memoryManager);
            java.util.List<com.formacraft.server.memory.PatchImpact> impacts = 
                analyzer.analyze(world.getRegistryKey().getValue(), origin, patches);
            
            // 对每个影响应用 Mutation
            for (com.formacraft.server.memory.PatchImpact impact : impacts) {
                // 获取建筑记忆（如果存在）
                com.formacraft.server.memory.ProjectMemory memory = null;
                if (impact.getTargetBuilding() != null) {
                    memory = memoryManager.getMemory(impact.getTargetBuilding().toString());
                }
                
                // 构建 GeneMutation
                com.formacraft.server.memory.GeneMutation mutation = 
                    com.formacraft.server.memory.MutationBuilder.build(impact, memory);
                
                if (mutation != null) {
                    // 应用 Mutation 到 Memory
                    memoryManager.applyMutation(
                        world.getRegistryKey().getValue(), mutation,
                        impact.getMinPos(),
                        impact.getMaxPos()
                    );
                }
            }
        } catch (Exception e) {
            // 静默失败，不影响 Patch 执行
            com.formacraft.FormacraftMod.LOGGER.warn("Failed to update memory from patch: {}", e.getMessage());
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
        var result = stacks(playerId).undo(world, com.formacraft.common.world.BlockMutationAccess.forWorld(world));
        if (result.delta() != null && !result.delta().patches().isEmpty()) {
            updateMemoryFromPatchUndo(world, result.delta().origin(), result.delta().patches());
        }
        return result;
    }
    public static PatchReplayHistory.ReplayResult redoDetailed(ServerWorld world, UUID playerId) {
        if (world == null || playerId == null) return PatchReplayHistory.ReplayResult.empty();
        var result = stacks(playerId).redo(world, com.formacraft.common.world.BlockMutationAccess.forWorld(world));
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
            // 获取 MemoryManager
            com.formacraft.server.memory.MemoryManager memoryManager = 
                com.formacraft.server.build.BuildExecutionService.getInstance().getMemoryManager();
            
            if (memoryManager == null) {
                return;
            }
            
            // Undo 是反向操作：从 after 恢复到 before
            // 我们需要分析 before 状态的影响
            // 反向分析真实 before 状态，保留被替换材质及属性
            com.formacraft.server.memory.PatchDiffAnalyzer analyzer = 
                new com.formacraft.server.memory.PatchDiffAnalyzer(memoryManager);
            
            java.util.List<com.formacraft.server.memory.PatchImpact> impacts = 
                analyzer.analyze(world.getRegistryKey().getValue(), origin, reversePatches);
            
            // 应用反向 Mutation
            for (com.formacraft.server.memory.PatchImpact impact : impacts) {
                com.formacraft.server.memory.ProjectMemory memory = null;
                if (impact.getTargetBuilding() != null) {
                    memory = memoryManager.getMemory(impact.getTargetBuilding().toString());
                }
                
                com.formacraft.server.memory.GeneMutation mutation = 
                    com.formacraft.server.memory.MutationBuilder.build(impact, memory);
                
                if (mutation != null) {
                    // 添加 undo 标记
                    java.util.Map<String, Object> delta = new java.util.HashMap<>(mutation.geneDelta());
                    delta.put("undone", true);
                    delta.put("undo_timestamp", System.currentTimeMillis());
                    
                    com.formacraft.server.memory.GeneMutation undoMutation = new com.formacraft.server.memory.GeneMutation(
                        mutation.buildingId(),
                        "Undo: " + mutation.reason(),
                        mutation.affectedParts(),
                        delta
                    );
                    
                    memoryManager.applyMutation(world.getRegistryKey().getValue(), undoMutation, impact.getMinPos(), impact.getMaxPos());
                }
            }
        } catch (Exception e) {
            com.formacraft.FormacraftMod.LOGGER.warn("Failed to update memory from patch undo: {}", e.getMessage());
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
