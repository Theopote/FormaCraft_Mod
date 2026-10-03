package com.formacraft.server.build;

import com.formacraft.common.patch.history.PatchReplayHistory;
import com.formacraft.common.patch.history.PatchTransaction;
import com.formacraft.common.world.BlockMutationAccess;
import com.formacraft.server.memory.BuildUndoMemoryUpdate;
import com.formacraft.server.memory.MemoryManager;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.function.Function;

/** Build undo shares replay checks and records progress against the registered memory UUID. */
public class UndoService {
    private static final int MAX_UNDO_PER_PLAYER = 10;
    private record BuildRecord(UUID transactionId, String memoryUuid, Identifier dimension, int total) {}
    private static final class PlayerHistory {
        final PatchReplayHistory replay = new PatchReplayHistory(MAX_UNDO_PER_PLAYER);
        final Deque<BuildRecord> builds = new ArrayDeque<>();
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
        record(player.getUuid(), entry.getWorld(), entry.getOrigin(), entry.getChanges(), entry.getMemoryUuid(),
            entry.getWorld().getRegistryKey().getValue());
    }
    void record(UUID playerId, Object world, BlockPos origin, List<BlockChange> changes) {
        record(playerId, world, origin, changes, null, null);
    }
    void record(UUID playerId, Object world, BlockPos origin, List<BlockChange> changes,
                String memoryUuid, Identifier dimension) {
        if (memoryUuid != null) Objects.requireNonNull(dimension, "dimension");
        Map<BlockPos, BlockState> before = new HashMap<>(), after = new HashMap<>();
        for (BlockChange change : changes) {
            BlockPos pos = change.getPos().toImmutable();
            before.putIfAbsent(pos, change.getFromState());
            after.put(pos, change.getToState());
        }
        var tx = PatchTransaction.fromSnapshots(origin, before, after);
        if (tx.before().isEmpty()) return;
        var history = undoStacks.computeIfAbsent(playerId, k -> new PlayerHistory());
        history.replay.record(world, tx);
        history.builds.push(new BuildRecord(UUID.randomUUID(), memoryUuid, dimension, tx.before().size()));
        while (history.builds.size() > MAX_UNDO_PER_PLAYER) history.builds.removeLast();
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
    PatchReplayHistory.ReplayResult undo(UUID playerId, Object world, BlockMutationAccess access) {
        retryMemoryUpdates(playerId);
        var history = undoStacks.get(playerId);
        if (history == null) return PatchReplayHistory.ReplayResult.empty();
        var build = history.builds.peek();
        var result = history.replay.undo(world, access);
        if (result.available() && !result.wrongWorld() && build != null) {
            if (build.memoryUuid() != null) {
                var update = new BuildUndoMemoryUpdate(build.transactionId(), build.memoryUuid(), build.dimension(),
                    build.total() - result.remaining(), build.total(), result.complete());
                pendingMemory.computeIfAbsent(playerId, k -> new LinkedHashMap<>()).put(build.transactionId(), update);
                retryMemoryUpdates(playerId);
            }
            if (result.complete()) history.builds.pop();
        }
        return result;
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
        var history = undoStacks.get(player.getUuid());
        return history == null ? 0 : history.replay.undoSize();
    }
    public void clear() { undoStacks.clear(); pendingMemory.clear(); }
}
