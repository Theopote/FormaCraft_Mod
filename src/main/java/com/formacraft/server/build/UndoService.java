package com.formacraft.server.build;

import com.formacraft.common.patch.history.PatchReplayHistory;
import com.formacraft.common.patch.history.PatchTransaction;
import com.formacraft.common.world.BlockMutationAccess;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Build undo shares the same retry/conflict/world checks as Patch history. */
public class UndoService {
    private static final int MAX_UNDO_PER_PLAYER = 10;
    private final Map<UUID, PatchReplayHistory> undoStacks = new HashMap<>();

    public void pushUndo(ServerPlayerEntity player, UndoEntry entry) {
        record(player.getUuid(), entry.getWorld(), entry.getOrigin(), entry.getChanges());
    }
    void record(UUID playerId, Object world, BlockPos origin, List<BlockChange> changes) {
        Map<BlockPos, BlockState> before = new HashMap<>(), after = new HashMap<>();
        for (BlockChange change : changes) {
            BlockPos pos = change.getPos().toImmutable();
            before.putIfAbsent(pos, change.getFromState());
            after.put(pos, change.getToState());
        }
        var tx = PatchTransaction.fromSnapshots(origin, before, after);
        undoStacks.computeIfAbsent(playerId, k -> new PatchReplayHistory(MAX_UNDO_PER_PLAYER)).record(world, tx);
    }
    public boolean undoLast(ServerPlayerEntity player) { return undoLastResult(player).complete(); }
    public PatchReplayHistory.ReplayResult undoLastResult(ServerPlayerEntity player) {
        if (!(player.getEntityWorld() instanceof ServerWorld world)) return PatchReplayHistory.ReplayResult.empty();
        return undo(player.getUuid(), world, BlockMutationAccess.forWorld(world));
    }
    PatchReplayHistory.ReplayResult undo(UUID playerId, Object world, BlockMutationAccess access) {
        var history = undoStacks.get(playerId);
        return history == null ? PatchReplayHistory.ReplayResult.empty() : history.undo(world, access);
    }
    public int getUndoStackSize(ServerPlayerEntity player) {
        var history = undoStacks.get(player.getUuid());
        return history == null ? 0 : history.undoSize();
    }
    public void clear() { undoStacks.clear(); }
}
