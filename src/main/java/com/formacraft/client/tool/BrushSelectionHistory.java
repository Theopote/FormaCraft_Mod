package com.formacraft.client.tool;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;

/** One delta per gesture; no copy of the full selection on every tick. */
final class BrushSelectionHistory {
    private record Change(LongOpenHashSet added, LongOpenHashSet removed) {}
    private final LongOpenHashSet selected;
    private final ArrayDeque<Change> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private Change pending;
    private static final int LIMIT = 32;

    BrushSelectionHistory(LongOpenHashSet selected) { this.selected = selected; }

    void begin() {
        commit();
        pending = new Change(new LongOpenHashSet(), new LongOpenHashSet());
    }

    void add(long pos) {
        if (pending == null) begin();
        if (selected.add(pos) && !pending.removed.remove(pos)) pending.added.add(pos);
    }

    void remove(long pos) {
        if (pending == null) begin();
        if (selected.remove(pos) && !pending.added.remove(pos)) pending.removed.add(pos);
    }

    void commit() {
        if (pending == null) return;
        if (!pending.added.isEmpty() || !pending.removed.isEmpty()) {
            undo.addLast(pending);
            while (undo.size() > LIMIT) undo.removeFirst();
            redo.clear();
        }
        pending = null;
    }

    void cancel() {
        if (pending == null) return;
        selected.removeAll(pending.added);
        selected.addAll(pending.removed);
        pending = null;
    }

    void clear() {
        begin();
        for (long pos : selected.toLongArray()) remove(pos);
        commit();
    }

    boolean canUndo() { return !undo.isEmpty() || pending != null && (!pending.added.isEmpty() || !pending.removed.isEmpty()); }
    boolean canRedo() { return !redo.isEmpty(); }

    boolean undo() {
        commit();
        if (undo.isEmpty()) return false;
        Change change = undo.removeLast();
        selected.removeAll(change.added);
        selected.addAll(change.removed);
        redo.addLast(change);
        return true;
    }

    boolean redo() {
        commit();
        if (redo.isEmpty()) return false;
        Change change = redo.removeLast();
        selected.removeAll(change.removed);
        selected.addAll(change.added);
        undo.addLast(change);
        return true;
    }
}
