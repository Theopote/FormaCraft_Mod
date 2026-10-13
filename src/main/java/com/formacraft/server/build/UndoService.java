package com.formacraft.server.build;

import com.formacraft.common.patch.history.PatchReplayHistory;
import com.formacraft.common.patch.history.PatchTransaction;
import com.formacraft.common.world.BlockMutationAccess;
import com.formacraft.common.world.BlockSnapshot;
import com.formacraft.server.memory.BuildUndoMemoryUpdate;
import com.formacraft.server.memory.MemoryManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.function.Function;

/** Build undo/redo shares replay checks and records progress against the registered memory UUID. */
public class UndoService {
    private static final int MAX_UNDO_PER_PLAYER = 10;
    private record BuildRecord(UUID transactionId, String memoryUuid, Identifier dimension, int total) {}
    private static final class PlayerHistory {
        final PatchReplayHistory replay = new PatchReplayHistory(MAX_UNDO_PER_PLAYER);
        final Deque<BuildRecord> builds = new ArrayDeque<>();
        final Deque<BuildRecord> redoBuilds = new ArrayDeque<>();
        PlayerHistory() {
            replay.setOnEvict(id -> {
                PlayerMutationLedger.get().remove(id);
                builds.removeIf(b -> b.transactionId().equals(id));
                redoBuilds.removeIf(b -> b.transactionId().equals(id));
            });
        }
    }
    private final Map<UUID, PlayerHistory> undoStacks = new HashMap<>();
    private final Map<UUID, Map<UUID, BuildUndoMemoryUpdate>> pendingMemory = new HashMap<>();
    private Function<BuildUndoMemoryUpdate, Boolean> memoryUpdater;

    public UndoService() { this(update -> false); }
    UndoService(Function<BuildUndoMemoryUpdate, Boolean> memoryUpdater) { this.memoryUpdater = memoryUpdater; }
    public void setMemoryManager(MemoryManager manager) {
        memoryUpdater = manager == null ? update -> false : manager::recordBuildUndo;
    }
    public void pushUndo(ServerPlayerEntity player, UndoEntry entry) {
        if (player == null || entry == null) return;
        pushUndo(player.getUuid(), entry);
    }

    /** 按玩家 UUID 记录撤销（玩家可离线）。 */
    public void pushUndo(UUID playerId, UndoEntry entry) {
        if (playerId == null || entry == null || entry.getWorld() == null) return;
        record(playerId, entry.getWorld(), entry.getOrigin(), entry.getChanges(), entry.getMemoryUuid(),
                entry.getWorld().getRegistryKey().getValue());
    }

    public void record(UUID playerId, Object world, BlockPos origin, List<BlockChange> changes) {
        record(playerId, world, origin, changes, null, null);
    }

    public void record(UUID playerId, Object world, BlockPos origin, List<BlockChange> changes,
                       String memoryUuid, Identifier dimension) {
        if (memoryUuid != null) Objects.requireNonNull(dimension, "dimension");
        Map<BlockPos, BlockSnapshot> before = new HashMap<>(), after = new HashMap<>();
        for (BlockChange change : changes) {
            BlockPos pos = change.getPos().toImmutable();
            before.putIfAbsent(pos, change.getFrom());
            after.put(pos, change.getTo());
        }
        var tx = PatchTransaction.fromSnapshots(origin, before, after);
        if (tx.before().isEmpty()) return;
        var history = undoStacks.computeIfAbsent(playerId, k -> new PlayerHistory());
        history.redoBuilds.clear();
        UUID transactionId = MutationHistoryCoordinator.recordBuild(playerId, history.replay, world, tx);
        if (transactionId == null) return;
        history.builds.push(new BuildRecord(transactionId, memoryUuid, dimension, tx.before().size()));
        while (history.builds.size() > MAX_UNDO_PER_PLAYER) {
            BuildRecord dropped = history.builds.removeLast();
            PlayerMutationLedger.get().remove(dropped.transactionId());
        }
    }

    public boolean undoLast(ServerPlayerEntity player) { return undoLastResult(player).complete(); }
    public PatchReplayHistory.ReplayResult undoLastResult(ServerPlayerEntity player) {
        if (!(player.getEntityWorld() instanceof ServerWorld world)) return PatchReplayHistory.ReplayResult.empty();
        var result = undo(player.getUuid(), world, BlockMutationAccess.forWorld(world));
        if (hasPendingMemory(player.getUuid())) {
            player.sendMessage(net.minecraft.text.Text.literal("方块恢复结果已记录；建筑记忆保存未完成，下次撤销命令会重试保存"), false);
        }
        return result;
    }

    public PatchReplayHistory.ReplayResult undo(UUID playerId, Object world, BlockMutationAccess access) {
        return undo(playerId, world, access, null);
    }

    /** @param expectedTx 非空时校验栈顶事务 ID，不匹配则 empty（由 Facade 处理幽灵账本）。 */
    public PatchReplayHistory.ReplayResult undo(UUID playerId, Object world, BlockMutationAccess access,
                                                UUID expectedTx) {
        retryMemoryUpdates(playerId);
        var history = undoStacks.get(playerId);
        if (history == null) return PatchReplayHistory.ReplayResult.empty();
        UUID top = history.replay.peekUndoTransactionId();
        if (expectedTx != null && (top == null || !expectedTx.equals(top))) {
            return PatchReplayHistory.ReplayResult.empty();
        }
        // 直接调用（非 Facade）时同步账本
        PlayerMutationLedger.Entry ledgerEntry = null;
        if (expectedTx == null && top != null) {
            var peek = PlayerMutationLedger.get().peekUndo(playerId);
            if (peek != null && peek.kind() == PlayerMutationLedger.Kind.BUILD
                    && peek.transactionId().equals(top)) {
                ledgerEntry = PlayerMutationLedger.get().popUndo(playerId);
            }
        }
        var build = history.builds.peek();
        if (build != null && top != null && !build.transactionId().equals(top)) {
            history.builds.removeIf(b -> !b.transactionId().equals(top));
            build = history.builds.peek();
        }
        var result = history.replay.undo(world, access);
        if (!result.available()) {
            if (ledgerEntry != null) PlayerMutationLedger.get().restoreUndo(playerId, ledgerEntry);
            return result;
        }
        if (result.wrongWorld() || !result.complete()) {
            if (ledgerEntry != null) PlayerMutationLedger.get().restoreUndo(playerId, ledgerEntry);
        }
        if (result.available() && !result.wrongWorld() && build != null) {
            if (build.memoryUuid() != null) {
                var update = new BuildUndoMemoryUpdate(build.transactionId(), build.memoryUuid(), build.dimension(),
                    build.total() - result.remaining(), build.total(), result.complete());
                pendingMemory.computeIfAbsent(playerId, k -> new LinkedHashMap<>()).put(build.transactionId(), update);
                retryMemoryUpdates(playerId);
            }
            if (result.complete()) {
                history.builds.pop();
                history.redoBuilds.push(build);
            }
        }
        return result;
    }

    public PatchReplayHistory.ReplayResult redo(UUID playerId, Object world, BlockMutationAccess access) {
        return redo(playerId, world, access, null);
    }

    public PatchReplayHistory.ReplayResult redo(UUID playerId, Object world, BlockMutationAccess access,
                                                UUID expectedTx) {
        retryMemoryUpdates(playerId);
        var history = undoStacks.get(playerId);
        if (history == null) return PatchReplayHistory.ReplayResult.empty();
        UUID top = history.replay.peekRedoTransactionId();
        if (expectedTx != null && (top == null || !expectedTx.equals(top))) {
            return PatchReplayHistory.ReplayResult.empty();
        }
        PlayerMutationLedger.Entry ledgerEntry = null;
        if (expectedTx == null && top != null) {
            var peek = PlayerMutationLedger.get().peekRedo(playerId);
            if (peek != null && peek.kind() == PlayerMutationLedger.Kind.BUILD
                    && peek.transactionId().equals(top)) {
                ledgerEntry = PlayerMutationLedger.get().popRedo(playerId);
            }
        }
        var build = history.redoBuilds.peek();
        if (build != null && top != null && !build.transactionId().equals(top)) {
            history.redoBuilds.removeIf(b -> !b.transactionId().equals(top));
            build = history.redoBuilds.peek();
        }
        var result = history.replay.redo(world, access);
        if (!result.available()) {
            if (ledgerEntry != null) PlayerMutationLedger.get().restoreRedo(playerId, ledgerEntry);
            return result;
        }
        if (result.wrongWorld() || !result.complete()) {
            if (ledgerEntry != null) PlayerMutationLedger.get().restoreRedo(playerId, ledgerEntry);
        }
        if (result.available() && !result.wrongWorld() && build != null) {
            if (result.complete()) {
                var pending = pendingMemory.get(playerId);
                if (pending != null) pending.remove(build.transactionId());
                if (build.memoryUuid() != null) {
                    try {
                        var reset = new BuildUndoMemoryUpdate(build.transactionId(), build.memoryUuid(),
                                build.dimension(), 0, build.total(), false);
                        pendingMemory.computeIfAbsent(playerId, k -> new LinkedHashMap<>())
                                .put(build.transactionId(), reset);
                        retryMemoryUpdates(playerId);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                history.redoBuilds.pop();
                history.builds.push(build);
            }
        }
        return result;
    }

    public UUID peekUndoTransactionId(UUID playerId) {
        var history = undoStacks.get(playerId);
        return history == null ? null : history.replay.peekUndoTransactionId();
    }

    public UUID peekRedoTransactionId(UUID playerId) {
        var history = undoStacks.get(playerId);
        return history == null ? null : history.replay.peekRedoTransactionId();
    }

    public boolean canRedo(UUID playerId) {
        var history = undoStacks.get(playerId);
        return history != null && history.replay.canRedo();
    }

    void retryMemoryUpdates(UUID playerId) {
        var pending = pendingMemory.get(playerId);
        if (pending == null) return;
        pending.entrySet().removeIf(entry -> {
            try { return Boolean.TRUE.equals(memoryUpdater.apply(entry.getValue())); }
            catch (RuntimeException exception) {
                com.formacraft.FormacraftMod.LOGGER.warn("Build undo memory synchronization failed", exception);
                return false;
            }
        });
        if (pending.isEmpty()) pendingMemory.remove(playerId);
    }
    boolean hasPendingMemory(UUID playerId) {
        var pending = pendingMemory.get(playerId);
        return pending != null && !pending.isEmpty();
    }
    public int getUndoStackSize(ServerPlayerEntity player) {
        return player == null ? 0 : getUndoStackSize(player.getUuid());
    }

    public int getUndoStackSize(UUID playerId) {
        if (playerId == null) return 0;
        var history = undoStacks.get(playerId);
        return history == null ? 0 : history.replay.undoSize();
    }
    public void clear() {
        undoStacks.clear();
        pendingMemory.clear();
        PlayerMutationLedger.get().clearAll();
    }
}
