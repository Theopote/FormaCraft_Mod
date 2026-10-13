package com.formacraft.server.build;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家级统一变更账本：按时间序记录 BUILD / PATCH，条目与底层事务 {@code transactionId} 一一对应。
 * <p>
 * 会话内存态：服务器停止时清空，不持久化。淘汰以底层栈回调 {@link #remove(UUID)} 为准。
 */
public final class PlayerMutationLedger {
    public enum Kind { BUILD, PATCH }

    public record Entry(UUID transactionId, UUID playerId, Kind kind, long seq) {
        public Entry {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(kind, "kind");
        }
    }

    private static final int LIMIT = 50;
    private static final PlayerMutationLedger INSTANCE = new PlayerMutationLedger();

    private final AtomicLong seq = new AtomicLong();
    private final Map<UUID, Deque<Entry>> undo = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Entry>> redo = new ConcurrentHashMap<>();

    public static PlayerMutationLedger get() {
        return INSTANCE;
    }

    public void record(UUID transactionId, UUID playerId, Kind kind) {
        if (transactionId == null || playerId == null || kind == null) return;
        remove(transactionId); // 防重复
        Deque<Entry> stack = undo.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        stack.push(new Entry(transactionId, playerId, kind, seq.incrementAndGet()));
        while (stack.size() > LIMIT) {
            Entry dropped = stack.removeLast();
            // 账本软上限：仅丢掉最旧账本项；底层栈应已通过 remove 同步更严的上限
        }
        clearRedo(playerId);
    }

    /** @deprecated use {@link #record(UUID, UUID, Kind)} */
    @Deprecated
    public void recordBuild(UUID playerId) {
        record(UUID.randomUUID(), playerId, Kind.BUILD);
    }

    /** @deprecated use {@link #record(UUID, UUID, Kind)} */
    @Deprecated
    public void recordPatch(UUID playerId) {
        record(UUID.randomUUID(), playerId, Kind.PATCH);
    }

    /** 底层栈淘汰或幽灵条目清理时移除对应 ID（undo/redo 两侧）。 */
    public void remove(UUID transactionId) {
        if (transactionId == null) return;
        for (Deque<Entry> stack : undo.values()) {
            stack.removeIf(e -> e.transactionId().equals(transactionId));
        }
        for (Deque<Entry> stack : redo.values()) {
            stack.removeIf(e -> e.transactionId().equals(transactionId));
        }
    }

    public void clearRedo(UUID playerId) {
        if (playerId == null) return;
        redo.remove(playerId);
    }

    /** 弹出最近一次可撤销条目；无则 null。 */
    public Entry popUndo(UUID playerId) {
        if (playerId == null) return null;
        Deque<Entry> stack = undo.get(playerId);
        if (stack == null || stack.isEmpty()) return null;
        Entry entry = stack.pop();
        redo.computeIfAbsent(playerId, k -> new ArrayDeque<>()).push(entry);
        return entry;
    }

    /** 弹出最近一次可重做条目；无则 null。 */
    public Entry popRedo(UUID playerId) {
        if (playerId == null) return null;
        Deque<Entry> stack = redo.get(playerId);
        if (stack == null || stack.isEmpty()) return null;
        Entry entry = stack.pop();
        undo.computeIfAbsent(playerId, k -> new ArrayDeque<>()).push(entry);
        return entry;
    }

    /** 撤销未完成时把条目放回 undo 栈顶（并移出 redo）。 */
    public void restoreUndo(UUID playerId, Entry entry) {
        if (playerId == null || entry == null) return;
        Deque<Entry> r = redo.get(playerId);
        if (r != null) r.remove(entry);
        Deque<Entry> u = undo.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        u.removeIf(e -> e.transactionId().equals(entry.transactionId()));
        u.push(entry);
    }

    /** 重做未完成时把条目放回 redo 栈顶。 */
    public void restoreRedo(UUID playerId, Entry entry) {
        if (playerId == null || entry == null) return;
        Deque<Entry> u = undo.get(playerId);
        if (u != null) u.removeIf(e -> e.transactionId().equals(entry.transactionId()));
        Deque<Entry> r = redo.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        r.removeIf(e -> e.transactionId().equals(entry.transactionId()));
        r.push(entry);
    }

    public boolean canUndo(UUID playerId) {
        Deque<Entry> stack = undo.get(playerId);
        return stack != null && !stack.isEmpty();
    }

    public boolean canRedo(UUID playerId) {
        Deque<Entry> stack = redo.get(playerId);
        return stack != null && !stack.isEmpty();
    }

    public Kind peekUndoKind(UUID playerId) {
        Deque<Entry> stack = undo.get(playerId);
        return stack == null || stack.isEmpty() ? null : stack.peek().kind();
    }

    public Kind peekRedoKind(UUID playerId) {
        Deque<Entry> stack = redo.get(playerId);
        return stack == null || stack.isEmpty() ? null : stack.peek().kind();
    }

    public Entry peekUndo(UUID playerId) {
        Deque<Entry> stack = undo.get(playerId);
        return stack == null || stack.isEmpty() ? null : stack.peek();
    }

    public Entry peekRedo(UUID playerId) {
        Deque<Entry> stack = redo.get(playerId);
        return stack == null || stack.isEmpty() ? null : stack.peek();
    }

    public int undoSize(UUID playerId) {
        Deque<Entry> stack = undo.get(playerId);
        return stack == null ? 0 : stack.size();
    }

    public int countKind(UUID playerId, Kind kind) {
        Deque<Entry> stack = undo.get(playerId);
        if (stack == null || kind == null) return 0;
        int n = 0;
        for (Entry e : stack) {
            if (e.kind() == kind) n++;
        }
        return n;
    }

    public void clear(UUID playerId) {
        if (playerId == null) return;
        undo.remove(playerId);
        redo.remove(playerId);
    }

    public void clearAll() {
        undo.clear();
        redo.clear();
    }
}
