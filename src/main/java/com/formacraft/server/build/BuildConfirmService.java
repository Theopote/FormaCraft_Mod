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
 * Transaction order:
 * <ol>
 *   <li>Capture immutable preview snapshot (digest + origin + generation)</li>
 *   <li>Validate dimension / quality / snapshot↔meta identity <b>before</b> enqueue</li>
 *   <li>Enqueue snapshot → receive {@code transactionId}</li>
 *   <li>CAS-consume that generation only; on mismatch cancel <b>that</b> transactionId
 *       and attempt restore (refuses to overwrite a newer preview/cancel)</li>
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
        BuildExecutionService.EnqueueResult enqueue(
                ServerWorld world, GeneratedStructure structure, String fbdSourcePlanJson);
    }

    @FunctionalInterface
    public interface TaskCanceler {
        /** Cancel exactly one task by transaction id. */
        boolean cancel(UUID transactionId);
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
            return origin != null && expectedOrigin.equals(origin);
        }
        return true;
    }

    /**
     * Post-CAS consume check: generation was already enforced by
     * {@link PreviewStorage#consumeIfGenerationMatches}; still verify digest/origin.
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
        return confirm(player, force, svc::enqueueBuildWithResult, svc::cancelBuildByTransactionId);
    }

    public static Result confirm(ServerPlayerEntity player, boolean force, Enqueuer enqueuer) {
        return confirm(player, force, enqueuer, BuildExecutionService.getInstance()::cancelBuildByTransactionId);
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

        BuildExecutionService.EnqueueResult enqueued = enqueuer == null
                ? BuildExecutionService.EnqueueResult.rejected("null_enqueuer")
                : enqueuer.enqueue(world, snapshot.structure(), snapshot.fbdSourcePlanJson());
        if (!enqueued.accepted()) {
            FormacraftMod.LOGGER.warn(
                    "Confirm refused (queue full): player={} dim={} origin={} blocks={} reason={}",
                    player.getName().getString(),
                    currentDim,
                    snapshot.origin() != null ? snapshot.origin().toShortString() : "?",
                    snapshot.structure().size(),
                    enqueued.reason());
            return new Result(Status.QUEUE_FULL, quality, snapshot.structure(), false);
        }

        UUID transactionId = enqueued.transactionId();
        GeneratedStructure consumed = PreviewStorage.consumeIfGenerationMatches(player, snapshot.generation());
        ConsumeOutcome consume = evaluateConsume(consumed, snapshot);
        if (!consume.matched()) {
            FormacraftMod.LOGGER.error(
                    "Confirm preview identity/generation mismatch after enqueue — cancelling tx={}: player={} expectedGen={} expectedDigest={}",
                    transactionId,
                    player.getName().getString(),
                    snapshot.generation(),
                    snapshot.digest());
            if (canceler != null && transactionId != null) {
                boolean cancelled = canceler.cancel(transactionId);
                FormacraftMod.LOGGER.warn(
                        "Cancel-by-transactionId after confirm mismatch: tx={} cancelled={}",
                        transactionId, cancelled);
            }
            boolean restored = PreviewStorage.restoreSnapshot(player, snapshot, quality);
            FormacraftMod.LOGGER.info(
                    "restoreSnapshot after confirm mismatch: restored={} snapshotGen={}",
                    restored, snapshot.generation());
            return new Result(Status.PREVIEW_IDENTITY_MISMATCH, quality, snapshot.structure(), false);
        }

        PreviewStorage.consumeSourcePlan(player);
        PreviewStorage.clearQualityReport(player);
        FormaCraftServerNetworking.sendClearOutline(player);

        boolean forced = force && quality != null && quality.hasError();
        FormacraftMod.LOGGER.info(
                "Preview confirmed: dimension={} origin={} blocks={} force={} gen={} tx={}",
                currentDim,
                consumed.getOrigin() != null ? consumed.getOrigin().toShortString() : "?",
                consumed.size(),
                force,
                snapshot.generation(),
                transactionId);
        return new Result(Status.ACCEPTED, quality, consumed, forced);
    }
}
