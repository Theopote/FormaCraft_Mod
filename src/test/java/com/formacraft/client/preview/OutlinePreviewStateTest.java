package com.formacraft.client.preview;

import com.formacraft.common.preview.OutlineBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OutlinePreviewStateTest {
    @AfterEach
    void tearDown() {
        OutlinePreviewState.clear();
    }

    @Test
    void setBlocksCachesOverallBoundsAndPublishesImmutableSnapshot() {
        OutlinePreviewState.setBlocks(List.of(
                new OutlineBlock(new BlockPos(0, 1, 0)),
                new OutlineBlock(new BlockPos(4, 3, 2))
        ));
        OutlinePreviewState.Snapshot snap = OutlinePreviewState.snapshot();
        assertTrue(OutlinePreviewState.isActive());
        assertNotNull(snap.overallBounds());
        assertEquals(0.0 - 0.02, snap.overallBounds().minX, 1e-6);
        assertEquals(5.0 + 0.02, snap.overallBounds().maxX, 1e-6);
        assertEquals(1.0 - 0.02, snap.overallBounds().minY, 1e-6);
        assertEquals(4.0 + 0.02, snap.overallBounds().maxY, 1e-6);
        assertThrows(UnsupportedOperationException.class, () -> snap.blocks().add(new OutlineBlock(BlockPos.ORIGIN)));
        assertThrows(UnsupportedOperationException.class, () -> snap.mergedBoxes().clear());
    }

    @Test
    void mergeOverflowFallsBackToOverallEnvelopeInsteadOfTruncatingUpperFloors() {
        List<OutlineBlock> blocks = new ArrayList<>();
        // Distinct non-adjacent cells across many Y layers → many merge boxes.
        for (int y = 0; y < 8; y++) {
            for (int i = 0; i < 4; i++) {
                blocks.add(new OutlineBlock(new BlockPos(i * 3, y, 0)));
            }
        }
        Box overall = OutlinePreviewState.computeOverallBounds(blocks);
        List<Box> merged = OutlinePreviewState.greedyMergeToBoxes(blocks, overall, 3);
        assertEquals(1, merged.size());
        assertEquals(overall, merged.getFirst());
        assertTrue(merged.getFirst().maxY > 7.0); // upper floors retained via envelope
    }

    @Test
    void clearPublishesEmptyInactiveSnapshot() {
        OutlinePreviewState.setBlocks(List.of(new OutlineBlock(new BlockPos(1, 2, 3))));
        OutlinePreviewState.clear();
        assertFalse(OutlinePreviewState.isActive());
        assertTrue(OutlinePreviewState.snapshot().blocks().isEmpty());
        assertNull(OutlinePreviewState.snapshot().overallBounds());
    }
}
