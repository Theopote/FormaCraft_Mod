package com.formacraft.common.patch.history;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.PatchExecutor;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
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

    private static final class Stacks {
        final Deque<PatchTransaction> undo = new ArrayDeque<>();
        final Deque<PatchTransaction> redo = new ArrayDeque<>();
    }

    private static final Map<UUID, Stacks> PER_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, PatchExecutor.ApplyResult> LAST_APPLY_RESULT = new ConcurrentHashMap<>();

    private static Stacks stacks(UUID playerId) {
        return PER_PLAYER.computeIfAbsent(playerId, k -> new Stacks());
    }

    public static boolean canUndo(UUID playerId) {
        return playerId != null && !stacks(playerId).undo.isEmpty();
    }

    public static boolean canRedo(UUID playerId) {
        return playerId != null && !stacks(playerId).redo.isEmpty();
    }

    public static void clear(UUID playerId) {
        if (playerId == null) return;
        Stacks s = stacks(playerId);
        s.undo.clear();
        s.redo.clear();
        LAST_APPLY_RESULT.remove(playerId);
    }

    /** 最近一次 apply 的统计（按玩家隔离）。 */
    public static PatchExecutor.ApplyResult getLastApplyResult(UUID playerId) {
        return playerId == null ? null : LAST_APPLY_RESULT.get(playerId);
    }

    /**
     * 应用 patch，并写入历史（事务）。
     * 同时更新 Memory 系统（Memory → Patch → Memory 闭环）
     */
    public static PatchExecutor.ApplyResult applyWithHistory(
            ServerWorld world, UUID playerId, BlockPos origin, List<BlockPatch> patches) {
        if (world == null || playerId == null) return null;
        if (origin == null || patches == null || patches.isEmpty()) return null;

        Set<BlockPos> affected = collectAffected(origin, patches);
        Map<BlockPos, BlockState> before = snapshot(world, affected);

        PatchExecutor.ApplyResult applyResult = PatchExecutor.apply(world, origin, patches);
        LAST_APPLY_RESULT.put(playerId, applyResult);

        Map<BlockPos, BlockState> after = snapshot(world, affected);

        PatchTransaction tx = new PatchTransaction(origin, List.copyOf(patches), before, after);

        Stacks s = stacks(playerId);
        s.undo.push(tx);
        s.redo.clear();

        while (s.undo.size() > MAX_HISTORY) {
            s.undo.removeLast();
        }
        
        // ========== Memory → Patch → Memory 闭环 ==========
        // 分析 Patch 影响并更新 Memory
        updateMemoryFromPatch(world, origin, patches);
        return applyResult;
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
                analyzer.analyze(origin, patches);
            
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
                        mutation,
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

    public static boolean undo(ServerWorld world, UUID playerId) {
        if (world == null || playerId == null) return false;
        Stacks s = stacks(playerId);
        if (s.undo.isEmpty()) return false;

        PatchTransaction tx = s.undo.pop();
        restore(world, tx.before());
        s.redo.push(tx);
        
        // ========== Undo 时反向更新 Memory ==========
        // 注意：Undo 是反向操作，需要反向 Mutation
        updateMemoryFromPatchUndo(world, tx);
        
        return true;
    }

    public static boolean redo(ServerWorld world, UUID playerId) {
        if (world == null || playerId == null) return false;
        Stacks s = stacks(playerId);
        if (s.redo.isEmpty()) return false;

        PatchTransaction tx = s.redo.pop();
        restore(world, tx.after());
        s.undo.push(tx);
        
        // ========== Redo 时再次更新 Memory ==========
        updateMemoryFromPatch(world, tx.origin(), tx.patches());
        
        return true;
    }
    
    /**
     * Undo 时的 Memory 更新（反向 Mutation）
     */
    private static void updateMemoryFromPatchUndo(ServerWorld world, PatchTransaction tx) {
        try {
            // 获取 MemoryManager
            com.formacraft.server.memory.MemoryManager memoryManager = 
                com.formacraft.server.build.BuildExecutionService.getInstance().getMemoryManager();
            
            if (memoryManager == null) {
                return;
            }
            
            // Undo 是反向操作：从 after 恢复到 before
            // 我们需要分析 before 状态的影响
            // 简化实现：分析原始 patch 的反向影响
            com.formacraft.server.memory.PatchDiffAnalyzer analyzer = 
                new com.formacraft.server.memory.PatchDiffAnalyzer(memoryManager);
            
            // 创建反向 patch（将 place 改为 remove，remove 改为 place）
            java.util.List<com.formacraft.common.patch.BlockPatch> reversePatches = 
                new java.util.ArrayList<>();
            for (com.formacraft.common.patch.BlockPatch patch : tx.patches()) {
                String reverseAction = com.formacraft.common.patch.BlockPatch.REMOVE.equals(patch.action())
                    ? com.formacraft.common.patch.BlockPatch.PLACE
                    : com.formacraft.common.patch.BlockPatch.REMOVE;
                reversePatches.add(new com.formacraft.common.patch.BlockPatch(
                    reverseAction, patch.dx(), patch.dy(), patch.dz(), patch.targetBlock()
                ));
            }
            
            java.util.List<com.formacraft.server.memory.PatchImpact> impacts = 
                analyzer.analyze(tx.origin(), reversePatches);
            
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
                    
                    memoryManager.applyMutation(undoMutation, impact.getMinPos(), impact.getMaxPos());
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

    private static Map<BlockPos, BlockState> snapshot(ServerWorld world, Set<BlockPos> positions) {
        Map<BlockPos, BlockState> map = new HashMap<>(positions.size());
        for (BlockPos pos : positions) {
            map.put(pos, world.getBlockState(pos));
        }
        return map;
    }

    private static void restore(ServerWorld world, Map<BlockPos, BlockState> states) {
        for (Map.Entry<BlockPos, BlockState> e : states.entrySet()) {
            world.setBlockState(e.getKey(), e.getValue(), 3);
        }
    }
}

