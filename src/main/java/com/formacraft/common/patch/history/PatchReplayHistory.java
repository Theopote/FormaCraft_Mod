package com.formacraft.common.patch.history;

import com.formacraft.common.world.BlockMutationAccess;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** In-memory, world-bound history. A partially restored entry remains on its source stack. */
public final class PatchReplayHistory {
    private static final class Entry {
        final Object world;
        final PatchTransaction transaction;
        boolean started;
        final Map<BlockPos, BlockState> pending = new LinkedHashMap<>();
        Entry(Object world, PatchTransaction transaction) {
            this.world = Objects.requireNonNull(world);
            this.transaction = transaction;
            pending.putAll(transaction.before());
        }
    }
    private final int limit;
    private final Deque<Entry> undo = new ArrayDeque<>();
    private final Deque<Entry> redo = new ArrayDeque<>();
    public PatchReplayHistory(int limit) { this.limit = Math.max(1, limit); }
    public boolean canUndo() { return !undo.isEmpty() && (redo.isEmpty() || !redo.peek().started); }
    public boolean canRedo() { return !redo.isEmpty() && (undo.isEmpty() || !undo.peek().started); }
    public int undoSize() { return undo.size(); }
    public void clear() { undo.clear(); redo.clear(); }
    public void record(Object world, PatchTransaction transaction) {
        if (transaction.before().isEmpty()) return;
        // A new branch discards pending redo, but completed redo writes must remain undoable.
        if (!redo.isEmpty() && redo.peek().started) {
            Entry partial = redo.peek();
            var before = new HashMap<>(partial.transaction.before());
            var after = new HashMap<>(partial.transaction.after());
            partial.pending.keySet().forEach(pos -> { before.remove(pos); after.remove(pos); });
            var completed = PatchTransaction.fromSnapshots(partial.transaction.origin(), before, after);
            if (!completed.before().isEmpty()) undo.push(new Entry(partial.world, completed));
        }
        undo.push(new Entry(world, transaction));
        redo.clear();
        while (undo.size() > limit) undo.removeLast();
    }
    public ReplayResult undo(Object world, BlockMutationAccess access) { return replay(world, access, true); }
    public ReplayResult redo(Object world, BlockMutationAccess access) { return replay(world, access, false); }

    private ReplayResult replay(Object world, BlockMutationAccess access, boolean reversing) {
        Deque<Entry> source = reversing ? undo : redo;
        Deque<Entry> destination = reversing ? redo : undo;
        if (source.isEmpty() || (reversing ? !canUndo() : !canRedo())) return ReplayResult.empty();
        Entry entry = source.peek();
        if (entry.world != world) return new ReplayResult(true, true, 0, 0, 0, 0, 0, entry.pending.size(), null);
        var expected = reversing ? entry.transaction.after() : entry.transaction.before();
        Map<BlockPos, BlockState> actualBefore = new HashMap<>();
        Map<BlockPos, BlockState> actualAfter = new HashMap<>();
        int changed = 0, same = 0, blocked = 0, failed = 0, conflicts = 0;
        var iterator = entry.pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var item = iterator.next();
            BlockPos pos = item.getKey();
            if (!access.isInsideHeight(pos) || !access.isChunkReady(pos)) { blocked++; continue; }
            BlockState current = access.getState(pos);
            if (current.equals(item.getValue())) { same++; iterator.remove(); continue; }
            if (!current.equals(expected.get(pos))) { conflicts++; continue; }
            if (!access.setState(pos, item.getValue())) { failed++; continue; }
            BlockState after = access.getState(pos);
            actualBefore.put(pos, current);
            actualAfter.put(pos, after);
            if (!current.equals(after)) changed++;
            if (after.equals(item.getValue())) iterator.remove();
            else failed++;
        }
        var delta = PatchTransaction.fromSnapshots(entry.transaction.origin(), actualBefore, actualAfter);
        int remaining = entry.pending.size();
        entry.started = remaining > 0;
        if (remaining == 0) {
            source.pop();
            entry.pending.putAll(reversing ? entry.transaction.after() : entry.transaction.before());
            destination.push(entry);
        }
        return new ReplayResult(true, false, changed, same, blocked, failed, conflicts, remaining, delta);
    }

    public record ReplayResult(boolean available, boolean wrongWorld, int changed, int alreadyCorrect,
                               int blocked, int failedWrites, int conflicts, int remaining, PatchTransaction delta) {
        public static ReplayResult empty() { return new ReplayResult(false, false, 0, 0, 0, 0, 0, 0, null); }
        public boolean complete() { return available && !wrongWorld && remaining == 0; }
        public String summaryZh(String verb) {
            if (!available) return "没有可" + verb + "的修改";
            if (wrongWorld) return "请回到原建造世界后" + verb + "；历史已保留";
            String result = (complete() ? verb + "完成" : verb + "未完成") + "：实际恢复 " + changed + " 个方块";
            if (alreadyCorrect > 0) result += "，已是目标状态 " + alreadyCorrect + " 个";
            if (blocked > 0) result += "，越界或未加载 " + blocked + " 个";
            if (failedWrites > 0) result += "，写入失败 " + failedWrites + " 个";
            if (conflicts > 0) result += "，后续修改冲突 " + conflicts + " 个";
            if (remaining > 0) result += "，剩余 " + remaining + " 个；历史保留，可重试";
            return result;
        }
    }
}
