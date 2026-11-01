package com.formacraft.server.build;

import com.formacraft.server.build.quality.BuildQualityReport;
import com.formacraft.server.build.quality.BuildQualitySeverity;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BuildConfirmServiceTest {

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
