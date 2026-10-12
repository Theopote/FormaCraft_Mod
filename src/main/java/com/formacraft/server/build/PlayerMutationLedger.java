package com.formacraft.server.build;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家级统一变更账本：按时间序记录 BUILD / PATCH 条目，供 {@link MutationHistoryFacade} 倒序撤销。
 * <p>
 * 不合并执行器栈；各栈仍各自维护细节。账本上限 50（与 Patch 历史对齐）。
 * 会话内存态：服务器停止时清空，不持久化。
 */
public final class PlayerMutationLedger {
    public enum Kind { BUILD, PATCH }

    public record Entry(UUID playerId, Kind kind, long seq) {}

    private static final int LIMIT = 50;
    private static final PlayerMutationLedger INSTANCE = new PlayerMutationLedger();

    private final AtomicLong seq = new AtomicLong();
    private final Map<UUID, Deque<Entry>> undo = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Entry>> redo = new ConcurrentHashMap<>();

    public static PlayerMutationLedger get() {
        return INSTANCE;
    }

    public void recordBuild(UUID playerId) {
        record(playerId, Kind.BUILD);
    }

    public void recordPatch(UUID playerId) {
        record(playerId, Kind.PATCH);
    }

    private void record(UUID playerId, Kind kind) {
        if (playerId == null || kind == null) return;
        Deque<Entry> stack = undo.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        stack.push(new Entry(playerId, kind, seq.incrementAndGet()));
        while (stack.size() > LIMIT) stack.removeLast();
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
        if (!u.contains(entry)) u.push(entry);
    }

    /** 重做未完成时把条目放回 redo 栈顶。 */
    public void restoreRedo(UUID playerId, Entry entry) {
        if (playerId == null || entry == null) return;
        Deque<Entry> u = undo.get(playerId);
        if (u != null) u.remove(entry);
        Deque<Entry> r = redo.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        if (!r.contains(entry)) r.push(entry);
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
