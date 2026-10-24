package com.formacraft.common.patch.history;

import com.formacraft.common.world.BlockMutationAccess;
import com.formacraft.common.world.BlockSnapshot;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.function.Consumer;

/** In-memory, world-bound history. A partially restored entry remains on its source stack. */
public final class PatchReplayHistory {
    private static final class Entry {
        final UUID transactionId;
        final Object world;
        final PatchTransaction transaction;
        boolean started;
        final Map<BlockPos, BlockSnapshot> pending = new LinkedHashMap<>();
        // Preserve the observed source until a post-write read confirms the actual outcome.
        final Map<BlockPos, BlockSnapshot> unconfirmed = new HashMap<>();
        Entry(UUID transactionId, Object world, PatchTransaction transaction) {
            this.transactionId = Objects.requireNonNull(transactionId);
            this.world = Objects.requireNonNull(world);
            this.transaction = transaction;
            pending.putAll(transaction.before());
        }
    }
    private final int limit;
    private final Deque<Entry> undo = new ArrayDeque<>();
    private final Deque<Entry> redo = new ArrayDeque<>();
    private Consumer<UUID> onEvict = id -> {};

    public PatchReplayHistory(int limit) { this.limit = Math.max(1, limit); }

    /** 淘汰旧事务时回调（用于同步账本）。 */
    public void setOnEvict(Consumer<UUID> onEvict) {
        this.onEvict = onEvict == null ? id -> {} : onEvict;
    }

    public boolean canUndo() { return !undo.isEmpty() && (redo.isEmpty() || !redo.peek().started); }
    public boolean canRedo() { return !redo.isEmpty() && (undo.isEmpty() || !undo.peek().started); }
    public int undoSize() { return undo.size(); }
    public void clear() { undo.clear(); redo.clear(); }

    public UUID peekUndoTransactionId() {
        return undo.isEmpty() ? null : undo.peek().transactionId;
    }

    public UUID peekRedoTransactionId() {
        return redo.isEmpty() ? null : redo.peek().transactionId;
    }

    /** 兼容旧调用：自动生成 transactionId。 */
    public void record(Object world, PatchTransaction transaction) {
        record(world, transaction, UUID.randomUUID());
    }

    /**
     * @return 因容量淘汰而移除的 transactionId 列表
     */
    public List<UUID> record(Object world, PatchTransaction transaction, UUID transactionId) {
        List<UUID> evicted = new ArrayList<>();
        if (transaction.before().isEmpty()) return evicted;
        UUID id = transactionId == null ? UUID.randomUUID() : transactionId;
        // A new branch discards pending redo, but completed redo writes must remain undoable.
        if (!redo.isEmpty() && redo.peek().started) {
            Entry partial = redo.peek();
            var before = new HashMap<>(partial.transaction.before());
            var after = new HashMap<>(partial.transaction.after());
            partial.pending.keySet().forEach(pos -> {
                if (!partial.unconfirmed.containsKey(pos)) { before.remove(pos); after.remove(pos); }
            });
            var completed = PatchTransaction.fromSnapshots(partial.transaction.origin(), before, after);
            if (!completed.before().isEmpty()) {
                undo.push(new Entry(partial.transactionId, partial.world, completed));
            }
        }
        // Redo 分支丢弃：账本侧由调用方 clearRedo；此处不再逐条 onEvict（ID 可能已转入 undo 部分完成）
        redo.clear();
        undo.push(new Entry(id, world, transaction));
        while (undo.size() > limit) {
            Entry old = undo.removeLast();
            evicted.add(old.transactionId);
            onEvict.accept(old.transactionId);
        }
        return evicted;
    }

    public ReplayResult undo(Object world, BlockMutationAccess access) { return replay(world, access, true); }
    public ReplayResult redo(Object world, BlockMutationAccess access) { return replay(world, access, false); }

    private ReplayResult replay(Object world, BlockMutationAccess access, boolean reversing) {
        Deque<Entry> source = reversing ? undo : redo;
        Deque<Entry> destination = reversing ? redo : undo;
        if (source.isEmpty() || (reversing ? !canUndo() : !canRedo())) return ReplayResult.empty();
        Entry entry = source.peek();
        if (entry.world != world) {
            return new ReplayResult(true, true, 0, 0, 0, 0, 0, entry.pending.size(), null, entry.transactionId);
        }
        var expected = reversing ? entry.transaction.after() : entry.transaction.before();
        Map<BlockPos, BlockSnapshot> actualBefore = new HashMap<>();
        Map<BlockPos, BlockSnapshot> actualAfter = new HashMap<>();
        int same = 0, blocked = 0, failed = 0, conflicts = 0;
        var iterator = entry.pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var item = iterator.next();
            BlockPos pos = item.getKey();
            try {
                if (!access.isInsideHeight(pos) || !access.isChunkReady(pos)) { blocked++; continue; }
                BlockSnapshot current = access.getSnapshot(pos);
                BlockSnapshot unconfirmedBefore = entry.unconfirmed.remove(pos);
                if (unconfirmedBefore != null && !unconfirmedBefore.equals(current)) {
                    actualBefore.put(pos, unconfirmedBefore);
                    actualAfter.put(pos, current);
                }
                if (ReplaySnapshotMatcher.matches(current, item.getValue())) { same++; iterator.remove(); continue; }
                BlockSnapshot expectedSource = expected.get(pos);
                if (expectedSource != null && !ReplaySnapshotMatcher.matches(current, expectedSource)) {
                    if (++conflicts <= 5) com.formacraft.FormacraftMod.LOGGER.warn(
                            "History {} conflict at {}: expected={}, actual={}, transaction={}",
                            reversing ? "undo" : "redo", pos, expectedSource.state(), current.state(), entry.transactionId);
                    continue;
                }
                entry.unconfirmed.put(pos, current);
                var write = access.writeSnapshot(pos, item.getValue());
                BlockSnapshot after = access.getSnapshot(pos);
                entry.unconfirmed.remove(pos);
                actualBefore.putIfAbsent(pos, current);
                actualAfter.put(pos, after);
                boolean matchesTarget = ReplaySnapshotMatcher.matches(after, item.getValue());
                boolean derivedPaneState = matchesTarget && !after.equals(item.getValue())
                        && item.getValue().blockEntityNbt() == null;
                boolean restored = matchesTarget && (write.isFullSuccess()
                        || derivedPaneState && write == com.formacraft.common.world.SnapshotWriteResult.STATE_ONLY);
                if (restored) iterator.remove();
                else if (++failed <= 5) com.formacraft.FormacraftMod.LOGGER.warn(
                        "History {} write incomplete at {}: result={}, target={}, actual={}, transaction={}",
                        reversing ? "undo" : "redo", pos, write, item.getValue().state(), after.state(), entry.transactionId);
            } catch (RuntimeException exception) {
                failed++;
                com.formacraft.FormacraftMod.LOGGER.warn("History replay failed at {}; position retained for retry", pos, exception);
            }
        }
        var delta = PatchTransaction.fromSnapshots(entry.transaction.origin(), actualBefore, actualAfter);
        int remaining = entry.pending.size();
        entry.started = remaining > 0;
        if (remaining == 0) {
            source.pop();
            entry.pending.putAll(reversing ? entry.transaction.after() : entry.transaction.before());
            destination.push(entry);
        }
        return new ReplayResult(true, false, delta.before().size(), same, blocked, failed, conflicts, remaining, delta,
                entry.transactionId);
    }

    public record ReplayResult(boolean available, boolean wrongWorld, int changed, int alreadyCorrect,
                               int blocked, int failedWrites, int conflicts, int remaining, PatchTransaction delta,
                               UUID transactionId) {
        public ReplayResult(boolean available, boolean wrongWorld, int changed, int alreadyCorrect,
                            int blocked, int failedWrites, int conflicts, int remaining, PatchTransaction delta) {
            this(available, wrongWorld, changed, alreadyCorrect, blocked, failedWrites, conflicts, remaining, delta, null);
        }
        public static ReplayResult empty() {
            return new ReplayResult(false, false, 0, 0, 0, 0, 0, 0, null, null);
        }
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
