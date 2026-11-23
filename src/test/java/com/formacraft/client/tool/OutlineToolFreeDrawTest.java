package com.formacraft.client.tool;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FREE_DRAW invalid-finish must not poison the next stroke's draft.
 */
class OutlineToolFreeDrawTest {
    @AfterEach
    void tearDown() {
        OutlineTool.INSTANCE.resetWorldState();
        setMode(OutlineMode.FREE_DRAW);
    }

    @Test
    void freeDrawInvalidFinishClearsDraft() throws Exception {
        setMode(OutlineMode.FREE_DRAW);
        List<BlockPos> draft = draftList();
        // Self-crossing bow-tie (invalid).
        draft.add(new BlockPos(0, 64, 0));
        draft.add(new BlockPos(4, 64, 4));
        draft.add(new BlockPos(4, 64, 0));
        draft.add(new BlockPos(0, 64, 4));
        assertFalse(ToolGeometry.validPolygon(ToolGeometry.normalizePolygon(draft)));

        Method finish = OutlineTool.class.getDeclaredMethod("finishPolygon");
        finish.setAccessible(true);
        try {
            finish.invoke(OutlineTool.INSTANCE);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            // HudToast may fail without a live client; draft cleanup must still have run first.
            if (!(ex.getCause() instanceof Exception) && !(ex.getCause() instanceof Error)) {
                throw ex;
            }
        }

        assertTrue(draftList().isEmpty(), "invalid FREE_DRAW finish must cancelDraft");
        assertFalse(isSelecting());
        assertFalse(isFreeDrawing());
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> draftList() throws Exception {
        Field f = OutlineTool.class.getDeclaredField("draft");
        f.setAccessible(true);
        return (List<BlockPos>) f.get(OutlineTool.INSTANCE);
    }

    private static void setMode(OutlineMode mode) {
        try {
            Field f = OutlineTool.class.getDeclaredField("mode");
            f.setAccessible(true);
            f.set(OutlineTool.INSTANCE, mode);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean isSelecting() throws Exception {
        Field f = OutlineTool.class.getDeclaredField("selecting");
        f.setAccessible(true);
        return f.getBoolean(OutlineTool.INSTANCE);
    }

    private static boolean isFreeDrawing() throws Exception {
        Field f = OutlineTool.class.getDeclaredField("freeDrawing");
        f.setAccessible(true);
        return f.getBoolean(OutlineTool.INSTANCE);
    }
}
