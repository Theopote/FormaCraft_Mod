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
    }

    /**
     * 应用 patch，并写入历史（事务）。
     */
    public static void applyWithHistory(ServerWorld world, UUID playerId, BlockPos origin, List<BlockPatch> patches) {
        if (world == null || playerId == null) return;
        if (origin == null || patches == null || patches.isEmpty()) return;

        Set<BlockPos> affected = collectAffected(origin, patches);
        Map<BlockPos, BlockState> before = snapshot(world, affected);

        PatchExecutor.apply(world, origin, patches);

        Map<BlockPos, BlockState> after = snapshot(world, affected);

        PatchTransaction tx = new PatchTransaction(origin, List.copyOf(patches), before, after);

        Stacks s = stacks(playerId);
        s.undo.push(tx);
        s.redo.clear();

        while (s.undo.size() > MAX_HISTORY) {
            s.undo.removeLast();
        }
    }

    public static boolean undo(ServerWorld world, UUID playerId) {
        if (world == null || playerId == null) return false;
        Stacks s = stacks(playerId);
        if (s.undo.isEmpty()) return false;

        PatchTransaction tx = s.undo.pop();
        restore(world, tx.before());
        s.redo.push(tx);
        return true;
    }

    public static boolean redo(ServerWorld world, UUID playerId) {
        if (world == null || playerId == null) return false;
        Stacks s = stacks(playerId);
        if (s.redo.isEmpty()) return false;

        PatchTransaction tx = s.redo.pop();
        restore(world, tx.after());
        s.undo.push(tx);
        return true;
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

