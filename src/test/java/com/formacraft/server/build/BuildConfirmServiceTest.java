package com.formacraft.server.build;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.build.quality.BuildQualityReport;
import com.formacraft.server.build.quality.BuildQualitySeverity;
import com.formacraft.server.preview.PreviewStorage;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BuildConfirmServiceTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void queueFullDoesNotConsumePreview() {
        AtomicBoolean consumed = new AtomicBoolean(false);
        var status = BuildConfirmService.commitIfEnqueued(
                true, true, true, true,
                null, false,
                false,
                () -> consumed.set(true)
        );
        assertEquals(BuildConfirmService.Status.QUEUE_FULL, status);
        assertFalse(consumed.get(), "preview must be retained when enqueue fails");
    }

    @Test
    void acceptedConsumesPreviewOnlyAfterEnqueueSuccess() {
        AtomicInteger order = new AtomicInteger(0);
        AtomicInteger enqueueStep = new AtomicInteger(-1);
        AtomicInteger consumeStep = new AtomicInteger(-1);

        // Simulate: enqueue succeeds first, then consume callback runs.
        boolean enqueueSucceeded = true;
        enqueueStep.set(order.incrementAndGet());
        var status = BuildConfirmService.commitIfEnqueued(
                true, true, true, true,
                null, false,
                enqueueSucceeded,
                () -> consumeStep.set(order.incrementAndGet())
        );
        assertEquals(BuildConfirmService.Status.ACCEPTED, status);
        assertEquals(1, enqueueStep.get());
        assertEquals(2, consumeStep.get());
    }

    @Test
    void qualityRejectDoesNotConsumeEvenIfEnqueueWouldSucceed() {
        AtomicBoolean consumed = new AtomicBoolean(false);
        BuildQualityReport report = new BuildQualityReport();
        report.add(BuildQualitySeverity.ERROR, "E", "bad placement");
        var status = BuildConfirmService.commitIfEnqueued(
                true, true, true, true,
                report, false,
                true,
                () -> consumed.set(true)
        );
        assertEquals(BuildConfirmService.Status.QUALITY_REJECTED, status);
        assertFalse(consumed.get());
    }

    @Test
    void fatalRejectEvenWithForceDoesNotConsume() {
        AtomicBoolean consumed = new AtomicBoolean(false);
        BuildQualityReport report = new BuildQualityReport();
        report.add(BuildQualitySeverity.FATAL, "F", "fatal");
        var status = BuildConfirmService.commitIfEnqueued(
                true, true, true, true,
                report, true,
                true,
                () -> consumed.set(true)
        );
        assertEquals(BuildConfirmService.Status.QUALITY_REJECTED, status);
        assertFalse(consumed.get());
    }

    @Test
    void worldMismatchAndStaleDoNotConsume() {
        AtomicBoolean consumed = new AtomicBoolean(false);
        assertEquals(BuildConfirmService.Status.WORLD_MISMATCH,
                BuildConfirmService.commitIfEnqueued(
                        true, true, false, true, null, false, true, () -> consumed.set(true)));
        assertEquals(BuildConfirmService.Status.STALE_PREVIEW,
                BuildConfirmService.commitIfEnqueued(
                        true, true, true, false, null, false, true, () -> consumed.set(true)));
        assertEquals(BuildConfirmService.Status.NO_PREVIEW,
                BuildConfirmService.commitIfEnqueued(
                        false, true, true, true, null, false, true, () -> consumed.set(true)));
        assertEquals(BuildConfirmService.Status.CANNOT_BUILD,
                BuildConfirmService.commitIfEnqueued(
                        true, false, true, true, null, false, true, () -> consumed.set(true)));
        assertFalse(consumed.get());
    }

    @Test
    void maxActiveTasksPerWorldIsDocumentedBound() {
        assertEquals(32, BuildExecutionService.MAX_ACTIVE_TASKS_PER_WORLD);
    }

    @Test
    void matchesBoundIdentityRequiresSameDigestAndOrigin() {
        var structure = new GeneratedStructure(
                null,
                new BlockPos(3, 64, 5),
                "hut",
                List.of(new PlannedBlock(new BlockPos(3, 64, 5), Blocks.STONE.getDefaultState()))
        );
        String digest = PreviewStorage.digestStructure(structure);
        assertTrue(BuildConfirmService.matchesBoundIdentity(structure, digest, new BlockPos(3, 64, 5)));
        assertFalse(BuildConfirmService.matchesBoundIdentity(null, digest, new BlockPos(3, 64, 5)));
        assertFalse(BuildConfirmService.matchesBoundIdentity(structure, "deadbeef", new BlockPos(3, 64, 5)));
        assertFalse(BuildConfirmService.matchesBoundIdentity(structure, digest, new BlockPos(9, 64, 5)));
    }

    @Test
    void evaluateConsumeMismatchRequiresCancellingEnqueuedTask() {
        var structure = new GeneratedStructure(
                null,
                new BlockPos(1, 70, 2),
                "hut",
                List.of(new PlannedBlock(new BlockPos(1, 70, 2), Blocks.STONE.getDefaultState()))
        );
        var snapshot = new PreviewStorage.PreviewSnapshot(
                structure,
                PreviewStorage.digestStructure(structure),
                new BlockPos(1, 70, 2),
                7L,
                null,
                null
        );
        assertTrue(BuildConfirmService.evaluateConsume(structure, snapshot).matched());
        assertFalse(BuildConfirmService.evaluateConsume(structure, snapshot).shouldCancelEnqueuedTask());

        assertFalse(BuildConfirmService.evaluateConsume(null, snapshot).matched());
        assertTrue(BuildConfirmService.evaluateConsume(null, snapshot).shouldCancelEnqueuedTask(),
                "null consume after enqueue must cancel the task");

        var other = new GeneratedStructure(
                null,
                new BlockPos(9, 70, 2),
                "hut",
                List.of(new PlannedBlock(new BlockPos(9, 70, 2), Blocks.STONE.getDefaultState()))
        );
        var mismatch = BuildConfirmService.evaluateConsume(other, snapshot);
        assertFalse(mismatch.matched());
        assertTrue(mismatch.shouldCancelEnqueuedTask());
    }

    @Test
    void identityMismatchAfterEnqueueCancelsTaskAndDoesNotAccept() {
        AtomicBoolean enqueued = new AtomicBoolean(false);
        AtomicInteger cancelCalls = new AtomicInteger();
        AtomicBoolean acceptedConsumeSideEffects = new AtomicBoolean(false);

        // Pure decision path mirroring confirm(): enqueue ok → consume mismatch → cancel.
        boolean enqueueOk = true;
        enqueued.set(enqueueOk);
        var consume = BuildConfirmService.evaluateConsume(null, new PreviewStorage.PreviewSnapshot(
                new GeneratedStructure(null, new BlockPos(0, 64, 0), "x",
                        List.of(new PlannedBlock(new BlockPos(0, 64, 0), Blocks.STONE.getDefaultState()))),
                "abc",
                new BlockPos(0, 64, 0),
                1L,
                null,
                null
        ));
        assertTrue(enqueueOk);
        assertTrue(consume.shouldCancelEnqueuedTask());
        if (consume.shouldCancelEnqueuedTask()) {
            cancelCalls.incrementAndGet();
        } else {
            acceptedConsumeSideEffects.set(true);
        }
        assertEquals(1, cancelCalls.get());
        assertFalse(acceptedConsumeSideEffects.get(), "must not clear preview side-effects on mismatch");
        assertEquals(BuildConfirmService.Status.PREVIEW_IDENTITY_MISMATCH,
                consume.matched() ? BuildConfirmService.Status.ACCEPTED
                        : BuildConfirmService.Status.PREVIEW_IDENTITY_MISMATCH);
    }

    @Test
    void queueFullKeepsPreviewAliveForRetry() {
        AtomicBoolean previewAlive = new AtomicBoolean(true);
        AtomicInteger enqueueAttempts = new AtomicInteger();
        // First attempt: queue full
        enqueueAttempts.incrementAndGet();
        var refused = BuildConfirmService.commitIfEnqueued(
                previewAlive.get(), true, true, true,
                null, false, false,
                () -> previewAlive.set(false)
        );
        assertEquals(BuildConfirmService.Status.QUEUE_FULL, refused);
        assertTrue(previewAlive.get());

        // Retry after capacity frees
        enqueueAttempts.incrementAndGet();
        var accepted = BuildConfirmService.commitIfEnqueued(
                previewAlive.get(), true, true, true,
                null, false, true,
                () -> previewAlive.set(false)
        );
        assertEquals(BuildConfirmService.Status.ACCEPTED, accepted);
        assertFalse(previewAlive.get());
        assertEquals(2, enqueueAttempts.get());
    }
}
