package com.formacraft.server.build;

import com.formacraft.FormacraftMod;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.server.build.quality.BuildQualityGate;
import com.formacraft.server.build.quality.BuildQualityReport;
import com.formacraft.server.network.FormaCraftServerNetworking;
import com.formacraft.server.preview.PreviewStorage;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;
import java.util.UUID;

/**
 * Preview → Confirm → Apply 的统一入口。
 * <p>
 * Transaction order (P0):
 * <ol>
 *   <li>Capture immutable preview snapshot (digest + origin + generation)</li>
 *   <li>Validate dimension / quality / snapshot↔meta identity <b>before</b> enqueue</li>
 *   <li>Enqueue the snapshot structure</li>
 *   <li>On enqueue success: consume only if storage still matches the snapshot;
 *       on mismatch cancel the enqueued task and restore the snapshot preview</li>
 *   <li>On enqueue failure: keep preview untouched</li>
 * </ol>
 */
public final class BuildConfirmService {
    private BuildConfirmService() {}

    public enum Status {
        ACCEPTED,
        NO_PREVIEW,
        STALE_PREVIEW,
        WORLD_MISMATCH,
        QUALITY_REJECTED,
        QUEUE_FULL,
        CANNOT_BUILD,
        /**
         * Snapshot/meta identity failed before enqueue, or consume no longer matched
         * the snapshot after enqueue (enqueued task must be cancelled).
         */
        PREVIEW_IDENTITY_MISMATCH
    }

    public record Result(Status status, BuildQualityReport quality, GeneratedStructure structure, boolean forced) {
        public static Result of(Status status) {
            return new Result(status, null, null, false);
        }

        public boolean accepted() {
            return status == Status.ACCEPTED;
        }
    }

    @FunctionalInterface
    public interface Enqueuer {
        boolean enqueue(ServerWorld world, GeneratedStructure structure, String fbdSourcePlanJson);
    }

    @FunctionalInterface
    public interface TaskCanceler {
        /** Cancel queued/active builds for the player in this world; return cancelled count. */
        int cancel(UUID playerId, ServerWorld world);
    }

    /**
     * Pure gate used by unit tests: enqueue must succeed before consume callback runs.
     */
    public static Status commitIfEnqueued(
            boolean hasPreview,
            boolean worldOk,
            boolean dimensionMatch,
            boolean previewValid,
            BuildQualityReport quality,
            boolean force,
            boolean enqueueSucceeded,
            Runnable onAccepted
    ) {
        if (!hasPreview) return Status.NO_PREVIEW;
        if (!worldOk) return Status.CANNOT_BUILD;
        if (!dimensionMatch) return Status.WORLD_MISMATCH;
        if (!previewValid) return Status.STALE_PREVIEW;

        BuildQualityGate.Decision gate = BuildQualityGate.decide(quality, force);
        if (gate == BuildQualityGate.Decision.DENY_FATAL
                || gate == BuildQualityGate.Decision.REQUIRE_FORCE) {
            return Status.QUALITY_REJECTED;
        }
        if (!enqueueSucceeded) {
            return Status.QUEUE_FULL;
        }
        if (onAccepted != null) {
            onAccepted.run();
        }
        return Status.ACCEPTED;
    }

    /**
     * Post-enqueue consume outcome: mismatch ⇒ cancel task and do not treat confirm as accepted.
     */
    public record ConsumeOutcome(boolean matched, boolean shouldCancelEnqueuedTask) {
        public static ConsumeOutcome ok() {
            return new ConsumeOutcome(true, false);
        }

        public static ConsumeOutcome mismatch() {
            return new ConsumeOutcome(false, true);
        }
    }

    public static boolean matchesBoundIdentity(
            GeneratedStructure candidate,
            String expectedDigest,
            BlockPos expectedOrigin
    ) {
        if (candidate == null) return false;
        if (expectedDigest != null && !expectedDigest.isEmpty()) {
            if (!Objects.equals(expectedDigest, PreviewStorage.digestStructure(candidate))) {
                return false;
            }
        }
        if (expectedOrigin != null) {
            BlockPos origin = candidate.getOrigin();
            if (origin == null || !expectedOrigin.equals(origin)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decide whether a consumed structure still matches the pre-enqueue snapshot.
     * Null consume (validate failed / already gone) is a mismatch → cancel enqueued work.
     */
    public static ConsumeOutcome evaluateConsume(
            GeneratedStructure consumed,
            PreviewStorage.PreviewSnapshot snapshot
    ) {
        if (snapshot == null) return ConsumeOutcome.mismatch();
        if (!matchesBoundIdentity(consumed, snapshot.digest(), snapshot.origin())) {
            return ConsumeOutcome.mismatch();
        }
        return ConsumeOutcome.ok();
    }

    public static Result confirm(ServerPlayerEntity player, boolean force) {
        BuildExecutionService svc = BuildExecutionService.getInstance();
        return confirm(player, force, svc::enqueueBuild, svc::cancelBuildsForPlayer);
    }

    public static Result confirm(ServerPlayerEntity player, boolean force, Enqueuer enqueuer) {
        return confirm(player, force, enqueuer, BuildExecutionService.getInstance()::cancelBuildsForPlayer);
    }

    public static Result confirm(
            ServerPlayerEntity player,
            boolean force,
            Enqueuer enqueuer,
            TaskCanceler canceler
    ) {
        if (player == null || !PreviewStorage.hasPreview(player)) {
            return Result.of(Status.NO_PREVIEW);
        }
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return Result.of(Status.CANNOT_BUILD);
        }

        PreviewStorage.PreviewMeta meta = PreviewStorage.getMeta(player);
        Identifier currentDim = world.getRegistryKey().getValue();
        boolean dimensionMatch = meta == null
                || meta.dimension() == null
                || meta.dimension().equals(currentDim);
        if (!dimensionMatch) {
            return Result.of(Status.WORLD_MISMATCH);
        }
        if (!PreviewStorage.validatePreview(player)) {
            return Result.of(Status.STALE_PREVIEW);
        }

        BuildQualityReport quality = PreviewStorage.getQualityReport(player);
        BuildQualityGate.Decision gate = BuildQualityGate.decide(quality, force);
        if (gate == BuildQualityGate.Decision.DENY_FATAL
                || gate == BuildQualityGate.Decision.REQUIRE_FORCE) {
            return new Result(Status.QUALITY_REJECTED, quality, null, false);
        }

        // 1) Immutable snapshot + pre-enqueue identity bind (digest / origin / generation).
        PreviewStorage.PreviewSnapshot snapshot = PreviewStorage.captureSnapshot(player);
        if (snapshot == null || snapshot.structure() == null) {
            return Result.of(Status.NO_PREVIEW);
        }
        if (!snapshot.matchesMeta(meta)) {
            FormacraftMod.LOGGER.error(
                    "Confirm refused: snapshot/meta identity mismatch before enqueue player={} gen={} digest={}",
                    player.getName().getString(),
                    snapshot.generation(),
                    snapshot.digest());
            return new Result(Status.PREVIEW_IDENTITY_MISMATCH, quality, snapshot.structure(), false);
        }

        // 2) Accept task with the bound snapshot (not a live mutable storage reference).
        boolean enqueued = enqueuer != null
                && enqueuer.enqueue(world, snapshot.structure(), snapshot.fbdSourcePlanJson());
        if (!enqueued) {
            FormacraftMod.LOGGER.warn(
                    "Confirm refused (queue full): player={} dim={} origin={} blocks={}",
                    player.getName().getString(),
                    currentDim,
                    snapshot.origin() != null ? snapshot.origin().toShortString() : "?",
                    snapshot.structure().size());
            return new Result(Status.QUEUE_FULL, quality, snapshot.structure(), false);
        }

        // 3) Consume only after enqueue; mismatch ⇒ cancel task + restore preview.
        GeneratedStructure consumed = PreviewStorage.consumeStructure(player);
        ConsumeOutcome consume = evaluateConsume(consumed, snapshot);
        if (!consume.matched()) {
            FormacraftMod.LOGGER.error(
                    "Confirm preview identity mismatch after enqueue — cancelling task: player={} expectedDigest={} expectedOrigin={} expectedGen={} consumedNull={}",
                    player.getName().getString(),
                    snapshot.digest(),
                    snapshot.origin() != null ? snapshot.origin().toShortString() : "?",
                    snapshot.generation(),
                    consumed == null);
            if (canceler != null) {
                int cancelled = canceler.cancel(player.getUuid(), world);
                FormacraftMod.LOGGER.warn(
                        "Cancelled {} build task(s) after confirm identity mismatch for player={}",
                        cancelled, player.getName().getString());
            }
            // Restore if consume removed storage, or reinforce if consume returned null.
            PreviewStorage.restoreSnapshot(player, snapshot, quality);
            return new Result(Status.PREVIEW_IDENTITY_MISMATCH, quality, snapshot.structure(), false);
        }

        PreviewStorage.consumeSourcePlan(player);
        PreviewStorage.clearQualityReport(player);
        FormaCraftServerNetworking.sendClearOutline(player);

        boolean forced = force && quality != null && quality.hasError();
        FormacraftMod.LOGGER.info(
                "Preview confirmed: dimension={} origin={} blocks={} force={} gen={}",
                currentDim,
                consumed.getOrigin() != null ? consumed.getOrigin().toShortString() : "?",
                consumed.size(),
                force,
                snapshot.generation());
        return new Result(Status.ACCEPTED, quality, consumed, forced);
    }
}
