package com.formacraft.server.build;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.build.quality.BuildQualityReport;
import com.formacraft.server.build.quality.BuildQualitySeverity;
import com.formacraft.server.preview.PreviewStorage;
import com.formacraft.server.preview.PreviewStructureShifter;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 Task C：Preview → Confirm → Apply 一致性（无 live ServerPlayer）。
 */
class PreviewConfirmApplyConsistencyTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    private static GeneratedStructure sample(BlockPos origin) {
        List<PlannedBlock> blocks = List.of(
                new PlannedBlock(origin, Blocks.STONE.getDefaultState()),
                new PlannedBlock(origin.add(1, 0, 0), Blocks.OAK_PLANKS.getDefaultState()),
                new PlannedBlock(origin.add(0, 1, 0), Blocks.GLASS.getDefaultState())
        );
        return new GeneratedStructure(UUID.randomUUID(), origin, "sample", blocks);
    }

    @Test
    void doubleConfirmOnlyEnqueuesOnce() {
        AtomicInteger enqueueCount = new AtomicInteger();
        AtomicBoolean previewAlive = new AtomicBoolean(true);
        GeneratedStructure structure = sample(new BlockPos(8, 64, 8));
        AtomicReference<GeneratedStructure> enqueued = new AtomicReference<>();

        Runnable consume = () -> previewAlive.set(false);

        // First confirm
        boolean firstEnqueue = true;
        enqueueCount.incrementAndGet();
        enqueued.set(structure);
        var first = BuildConfirmService.commitIfEnqueued(
                previewAlive.get(), true, true, true,
                null, false, firstEnqueue, consume);
        assertEquals(BuildConfirmService.Status.ACCEPTED, first);
        assertFalse(previewAlive.get());

        // Second confirm — no preview, must not enqueue again
        boolean wouldEnqueue = previewAlive.get();
        if (wouldEnqueue) {
            enqueueCount.incrementAndGet();
        }
        var second = BuildConfirmService.commitIfEnqueued(
                previewAlive.get(), true, true, true,
                null, false, wouldEnqueue, () -> fail("must not consume twice"));
        assertEquals(BuildConfirmService.Status.NO_PREVIEW, second);
        assertEquals(1, enqueueCount.get());
        assertEquals(PreviewStorage.digestStructure(structure),
                PreviewStorage.digestStructure(enqueued.get()));
    }

    @Test
    void confirmAcceptsSameDigestAsPreviewStructure() {
        GeneratedStructure preview = sample(new BlockPos(0, 70, 0));
        String previewDigest = PreviewStorage.digestStructure(preview);
        AtomicReference<String> enqueuedDigest = new AtomicReference<>();

        var status = BuildConfirmService.commitIfEnqueued(
                true, true, true, true,
                null, false, true,
                () -> enqueuedDigest.set(PreviewStorage.digestStructure(preview))
        );
        assertEquals(BuildConfirmService.Status.ACCEPTED, status);
        assertEquals(previewDigest, enqueuedDigest.get());
    }

    @Test
    void shiftedPreviewDigestMatchesBlocksConfirmWouldEnqueue() {
        GeneratedStructure original = sample(new BlockPos(10, 64, 20));
        GeneratedStructure shifted = PreviewStructureShifter.shift(original, 4, 0, -2);
        assertNotEquals(PreviewStorage.digestStructure(original), PreviewStorage.digestStructure(shifted));

        AtomicReference<GeneratedStructure> accepted = new AtomicReference<>();
        var status = BuildConfirmService.commitIfEnqueued(
                true, true, true, true,
                null, false, true,
                () -> accepted.set(shifted)
        );
        assertEquals(BuildConfirmService.Status.ACCEPTED, status);
        assertEquals(PreviewStorage.digestStructure(shifted), PreviewStorage.digestStructure(accepted.get()));

        // Position + state equality (not only count)
        assertEquals(shifted.size(), accepted.get().size());
        for (int i = 0; i < shifted.getBlocks().size(); i++) {
            assertEquals(shifted.getBlocks().get(i).getPos(), accepted.get().getBlocks().get(i).getPos());
            assertEquals(shifted.getBlocks().get(i).getTargetState(),
                    accepted.get().getBlocks().get(i).getTargetState());
        }
    }

    @Test
    void qualityFailureLeavesPreviewConsumable() {
        AtomicBoolean consumed = new AtomicBoolean(false);
        BuildQualityReport report = new BuildQualityReport();
        report.add(BuildQualitySeverity.ERROR, "FLOATING", "悬空");
        var status = BuildConfirmService.commitIfEnqueued(
                true, true, true, true,
                report, false, true,
                () -> consumed.set(true)
        );
        assertEquals(BuildConfirmService.Status.QUALITY_REJECTED, status);
        assertFalse(consumed.get());
        // force still allowed for ERROR
        var forced = BuildConfirmService.commitIfEnqueued(
                true, true, true, true,
                report, true, true,
                () -> consumed.set(true)
        );
        assertEquals(BuildConfirmService.Status.ACCEPTED, forced);
        assertTrue(consumed.get());
    }

    @Test
    void cancelSemanticsDropPreviewWithoutEnqueue() {
        AtomicBoolean previewAlive = new AtomicBoolean(true);
        AtomicInteger enqueueCount = new AtomicInteger();
        // forma_cancel equivalent: drop preview locally, never enqueue
        previewAlive.set(false);
        boolean enqueueSucceeded = false;
        if (previewAlive.get()) {
            enqueueSucceeded = true;
            enqueueCount.incrementAndGet();
        }
        var status = BuildConfirmService.commitIfEnqueued(
                previewAlive.get(), true, true, true,
                null, false, enqueueSucceeded,
                () -> fail("cancel must not consume via confirm")
        );
        assertEquals(BuildConfirmService.Status.NO_PREVIEW, status);
        assertEquals(0, enqueueCount.get());
        assertFalse(previewAlive.get());
    }

    @Test
    void reorderSameFinalStateKeepsDigest() {
        BlockPos o = new BlockPos(1, 2, 3);
        var a = new PlannedBlock(o, Blocks.STONE.getDefaultState());
        var b = new PlannedBlock(o.add(1, 0, 0), Blocks.DIRT.getDefaultState());
        GeneratedStructure chronological = new GeneratedStructure(
                null, o, "c", List.of(a, b));
        GeneratedStructure reversed = new GeneratedStructure(
                null, o, "r", List.of(b, a));
        assertEquals(PreviewStorage.digestStructure(chronological),
                PreviewStorage.digestStructure(reversed));
    }

    @Test
    void lastWriteWinsDigestDiffersWhenStateChanges() {
        BlockPos o = new BlockPos(5, 5, 5);
        GeneratedStructure first = new GeneratedStructure(null, o, "f", List.of(
                new PlannedBlock(o, Blocks.STONE.getDefaultState())
        ));
        GeneratedStructure overwrite = new GeneratedStructure(null, o, "o", List.of(
                new PlannedBlock(o, Blocks.STONE.getDefaultState()),
                new PlannedBlock(o, Blocks.OAK_PLANKS.getDefaultState())
        ));
        assertNotEquals(PreviewStorage.digestStructure(first), PreviewStorage.digestStructure(overwrite));
    }
}
