package com.formacraft.client.tool;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Column-index erase contract: O(|hover|) removal without scanning the whole selection. */
class BrushColumnIndexTest {
    @Test
    void columnKeyEraseRemovesOnlyMatchingXz() {
        LongOpenHashSet selected = new LongOpenHashSet();
        Long2LongOpenHashMap columns = new Long2LongOpenHashMap();
        columns.defaultReturnValue(Long.MIN_VALUE);

        long a = BlockPos.asLong(1, 64, 2);
        long b = BlockPos.asLong(5, 70, 6);
        long c = BlockPos.asLong(1, 80, 2); // same xz as a — index keeps latest
        selected.add(a);
        selected.add(b);
        columns.put(BrushTool.columnKey(1, 2), a);
        columns.put(BrushTool.columnKey(5, 6), b);
        columns.put(BrushTool.columnKey(1, 2), c);

        long key = BrushTool.columnKey(1, 2);
        long existing = columns.get(key);
        assertEquals(c, existing);
        selected.remove(existing);
        columns.remove(key);

        assertFalse(selected.contains(c));
        assertTrue(selected.contains(b));
        assertEquals(Long.MIN_VALUE, columns.get(key));
    }

    @Test
    void maxSelectedConstantIsBounded() {
        assertTrue(BrushTool.MAX_SELECTED >= 1000);
        assertTrue(BrushTool.MAX_SELECTED <= 100_000);
    }
}
