package com.formacraft.client.tool;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BrushSelectionHistoryTest {
    @Test void multipleTicksAreOneUndoableStroke() {
        var selected = new LongOpenHashSet();
        var history = new BrushSelectionHistory(selected);
        history.begin();
        history.add(1); history.add(2); history.add(1);
        history.commit();
        assertEquals(2, selected.size());
        assertTrue(history.undo());
        assertTrue(selected.isEmpty());
        assertFalse(history.undo());
        assertTrue(history.redo());
        assertTrue(selected.contains(1) && selected.contains(2));
    }

    @Test void erasingAndClearRestoreTheExactPreviousSelection() {
        var selected = new LongOpenHashSet(new long[]{1, 2, 3});
        var history = new BrushSelectionHistory(selected);
        history.begin(); history.remove(2); history.commit();
        history.clear();
        assertTrue(selected.isEmpty());
        history.undo();
        assertEquals(new LongOpenHashSet(new long[]{1, 3}), selected);
        history.undo();
        assertEquals(new LongOpenHashSet(new long[]{1, 2, 3}), selected);
        history.redo(); history.redo();
        assertTrue(selected.isEmpty());
    }

    @Test void cancelRestoresActiveStrokeWithoutLosingOlderHistory() {
        var selected = new LongOpenHashSet();
        var history = new BrushSelectionHistory(selected);
        history.begin(); history.add(1); history.commit();
        history.begin(); history.remove(1); history.add(2); history.cancel();
        assertEquals(new LongOpenHashSet(new long[]{1}), selected);
        assertTrue(history.undo());
        assertTrue(selected.isEmpty());
    }

    @Test void newChangesAfterUndoDiscardRedoButNoOpsDoNot() {
        var selected = new LongOpenHashSet();
        var history = new BrushSelectionHistory(selected);
        history.begin(); history.add(1); history.commit(); history.undo();
        history.begin(); history.add(2); history.remove(2); history.commit();
        assertTrue(history.canRedo());
        history.begin(); history.add(3); history.commit();
        assertFalse(history.canRedo());
        assertFalse(history.redo());
        assertEquals(new LongOpenHashSet(new long[]{3}), selected);
    }

    @Test void undoWhileHoldingTheMouseCommitsAndRevertsOnlyThatStroke() {
        var selected = new LongOpenHashSet(new long[]{5});
        var history = new BrushSelectionHistory(selected);
        history.begin(); history.add(6); history.remove(5);
        history.undo();
        assertEquals(new LongOpenHashSet(new long[]{5}), selected);
        history.redo();
        assertEquals(new LongOpenHashSet(new long[]{6}), selected);
    }

    @Test void historyEvictsOldestStrokesAtTheLimit() {
        var selected = new LongOpenHashSet();
        var history = new BrushSelectionHistory(selected);
        for (int i = 0; i < 40; i++) {
            history.begin(); history.add(i); history.commit();
        }
        int count = 0;
        while (history.undo()) count++;
        assertEquals(32, count);
        assertEquals(8, selected.size());
    }
}
