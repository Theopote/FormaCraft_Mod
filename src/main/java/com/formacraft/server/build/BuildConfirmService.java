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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Preview → Confirm → Apply 的统一入口。
 * <p>
 * 事务顺序：校验预览与质量 → 入队成功 → 再消费预览并清轮廓。
 * 入队失败时保留预览结构与 FBD 来源，避免“提示已开始建造却无任务且预览已丢”。
 * <p>
 * After enqueue, consume must return the same preview identity (digest + origin)
 * that was submitted; otherwise confirm is not treated as successful.
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
        /** Enqueue succeeded but consumed preview did not match the bound identity. */
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

    /**
     * 纯事务决策（可单测）：仅当 {@code enqueue} 返回 true 时调用 {@code onAccepted}（消费预览）。
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
     * Verify that a consumed structure matches the identity bound at confirm time.
     */
    public static boolean matchesBoundIdentity(
            GeneratedStructure consumed,
            String expectedDigest,
            BlockPos expectedOrigin
    ) {
        if (consumed == null) return false;
        if (expectedDigest != null && !expectedDigest.isEmpty()) {
            if (!Objects.equals(expectedDigest, PreviewStorage.digestStructure(consumed))) {
                return false;
            }
        }
        if (expectedOrigin != null) {
            BlockPos origin = consumed.getOrigin();
            if (origin == null || !expectedOrigin.equals(origin)) {
                return false;
            }
        }
        return true;
    }

    public static Result confirm(ServerPlayerEntity player, boolean force) {
        return confirm(player, force, BuildExecutionService.getInstance()::enqueueBuild);
    }

    public static Result confirm(ServerPlayerEntity player, boolean force, Enqueuer enqueuer) {
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

        GeneratedStructure structure = PreviewStorage.getStructure(player);
        if (structure == null) {
            return Result.of(Status.NO_PREVIEW);
        }
        String fbdSourcePlan = PreviewStorage.peekSourcePlan(player);

        final String boundDigest = meta != null ? meta.structureDigest() : PreviewStorage.digestStructure(structure);
        final BlockPos boundOrigin = meta != null && meta.origin() != null
                ? meta.origin().toImmutable()
                : (structure.getOrigin() != null ? structure.getOrigin().toImmutable() : null);

        AtomicBoolean identityOk = new AtomicBoolean(true);
        AtomicReference<GeneratedStructure> consumedRef = new AtomicReference<>();

        boolean enqueued = enqueuer != null && enqueuer.enqueue(world, structure, fbdSourcePlan);
        Status status = commitIfEnqueued(
                true,
                true,
                true,
                true,
                quality,
                force,
                enqueued,
                () -> {
                    GeneratedStructure consumed = PreviewStorage.consumeStructure(player);
                    if (!matchesBoundIdentity(consumed, boundDigest, boundOrigin)) {
                        identityOk.set(false);
                        FormacraftMod.LOGGER.error(
                                "Confirm preview identity mismatch after enqueue: player={} expectedDigest={} expectedOrigin={} consumedNull={} consumedDigest={} consumedOrigin={}",
                                player.getName().getString(),
                                boundDigest,
                                boundOrigin != null ? boundOrigin.toShortString() : "?",
                                consumed == null,
                                consumed != null ? PreviewStorage.digestStructure(consumed) : "n/a",
                                consumed != null && consumed.getOrigin() != null
                                        ? consumed.getOrigin().toShortString() : "n/a");
                    } else {
                        consumedRef.set(consumed);
                    }
                    PreviewStorage.consumeSourcePlan(player);
                    PreviewStorage.clearQualityReport(player);
                    FormaCraftServerNetworking.sendClearOutline(player);
                }
        );
        if (status == Status.QUEUE_FULL) {
            FormacraftMod.LOGGER.warn(
                    "Confirm refused (queue full): player={} dim={} origin={} blocks={}",
                    player.getName().getString(),
                    currentDim,
                    structure.getOrigin() != null ? structure.getOrigin().toShortString() : "?",
                    structure.size());
            return new Result(Status.QUEUE_FULL, quality, structure, false);
        }
        if (status != Status.ACCEPTED) {
            return new Result(status, quality, structure, false);
        }
        if (!identityOk.get()) {
            return new Result(Status.PREVIEW_IDENTITY_MISMATCH, quality, structure, false);
        }

        GeneratedStructure confirmed = consumedRef.get() != null ? consumedRef.get() : structure;
        boolean forced = force && quality != null && quality.hasError();
        FormacraftMod.LOGGER.info(
                "Preview confirmed: dimension={} origin={} blocks={} force={}",
                currentDim,
                confirmed.getOrigin() != null ? confirmed.getOrigin().toShortString() : "?",
                confirmed.size(),
                force);
        return new Result(Status.ACCEPTED, quality, confirmed, forced);
    }
}
