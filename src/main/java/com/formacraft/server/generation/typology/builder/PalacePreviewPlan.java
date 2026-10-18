package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.WorldPatchTransaction;
import com.formacraft.common.typology.TypologyGenerationException;
import com.formacraft.common.world.BlockMutationAccess;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable confirmed preview: patches + build-context identity + diagnostics.
 * Apply must validate context then execute these patches — never silently regenerate.
 */
public final class PalacePreviewPlan {

    public enum ApplyRejectReason {
        OK,
        NOT_EXECUTABLE,
        CONTEXT_MISMATCH,
        TERRAIN_STALE,
        EMPTY_PATCHES,
        WORLD_MISSING
    }

    public record ApplyValidation(ApplyRejectReason reason, String detail) {
        public boolean ok() {
            return reason == ApplyRejectReason.OK;
        }
    }

    private final TieredMountainPalaceBuilder.GenerateStatus status;
    private final List<BlockPatch> patches;
    private final PalaceBuildContextFingerprint context;
    private final List<TieredMountainPalaceBuilder.BuildDiagnostic> diagnostics;
    private final String planId;

    public PalacePreviewPlan(
            TieredMountainPalaceBuilder.GenerateStatus status,
            List<BlockPatch> patches,
            PalaceBuildContextFingerprint context,
            List<TieredMountainPalaceBuilder.BuildDiagnostic> diagnostics
    ) {
        this.status = status == null ? TieredMountainPalaceBuilder.GenerateStatus.FAILED : status;
        this.patches = patches == null ? List.of() : List.copyOf(patches);
        this.context = Objects.requireNonNull(context, "context");
        this.diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        this.planId = context.contextHash();
    }

    public TieredMountainPalaceBuilder.GenerateStatus status() { return status; }
    public List<BlockPatch> patches() { return patches; }
    public PalaceBuildContextFingerprint context() { return context; }
    public List<TieredMountainPalaceBuilder.BuildDiagnostic> diagnostics() { return diagnostics; }
    public String planId() { return planId; }

    public boolean executable() {
        return status == TieredMountainPalaceBuilder.GenerateStatus.SUCCESS && !patches.isEmpty();
    }

    /**
     * Re-capture terrain at the plan origin/facing and compare content + full context hashes.
     */
    public ApplyValidation validateForApply(
            ServerWorld world,
            BlockPos origin,
            Direction facing,
            Map<String, Object> currentParams,
            String paletteId,
            int mx,
            int pathStartZ,
            int padY,
            int maxDrop,
            PalaceTerrainSnapshot.LocalTransform transform
    ) {
        if (!executable()) {
            return new ApplyValidation(ApplyRejectReason.NOT_EXECUTABLE,
                    status.name().toLowerCase(Locale.ROOT));
        }
        if (world == null || origin == null) {
            return new ApplyValidation(ApplyRejectReason.WORLD_MISSING, "world_or_origin_null");
        }
        if (patches.isEmpty()) {
            return new ApplyValidation(ApplyRejectReason.EMPTY_PATCHES, "no_patches");
        }
        Direction face = facing == null ? Direction.SOUTH : facing;
        if (!origin.equals(context.origin()) || face != context.facing()) {
            return new ApplyValidation(ApplyRejectReason.CONTEXT_MISMATCH,
                    "origin_or_facing_changed");
        }
        String worldKey = worldKeyOf(world);
        if (!worldKey.equals(context.worldKey())) {
            return new ApplyValidation(ApplyRejectReason.CONTEXT_MISMATCH,
                    "world=" + worldKey + "_expected=" + context.worldKey());
        }
        PalaceTerrainSnapshot live = PalaceTerrainSnapshot.capture(
                world, origin, face, mx, pathStartZ, padY, maxDrop, transform);
        if (!live.contentHash().equals(context.terrainContentHash())) {
            return new ApplyValidation(ApplyRejectReason.TERRAIN_STALE,
                    "live=" + live.contentHash() + "_plan=" + context.terrainContentHash());
        }
        PalaceBuildContextFingerprint liveCtx = PalaceBuildContextFingerprint.create(
                worldKey, origin, face, currentParams, live.contentHash(), paletteId);
        if (!liveCtx.contextHash().equals(context.contextHash())) {
            return new ApplyValidation(ApplyRejectReason.CONTEXT_MISMATCH,
                    "liveCtx=" + liveCtx.contextHash() + "_plan=" + context.contextHash());
        }
        return new ApplyValidation(ApplyRejectReason.OK, "");
    }

    /**
     * Validate then execute the confirmed patches via {@link WorldPatchTransaction}.
     * Does not regenerate geometry.
     */
    public WorldPatchTransaction.Result applyConfirmed(
            ServerWorld world,
            UUID playerId,
            BlockPos origin,
            Direction facing,
            Map<String, Object> currentParams,
            String paletteId,
            int mx,
            int pathStartZ,
            int padY,
            int maxDrop,
            PalaceTerrainSnapshot.LocalTransform transform
    ) {
        ApplyValidation v = validateForApply(
                world, origin, facing, currentParams, paletteId, mx, pathStartZ, padY, maxDrop, transform);
        if (!v.ok()) {
            throw new TypologyGenerationException(
                    TieredMountainPalaceBuilder.TYPOLOGY_ID,
                    "PREVIEW_APPLY_" + v.reason().name(),
                    "palace preview apply rejected: " + v.reason() + " " + v.detail(),
                    List.of(v.reason().name() + ":" + v.detail())
            );
        }
        if (playerId != null) {
            var hist = com.formacraft.server.build.PatchHistoryManager.applyWithHistory(
                    world, playerId, origin, patches);
            if (hist != null) {
                return hist;
            }
        }
        BlockMutationAccess access = BlockMutationAccess.forWorld(world);
        return WorldPatchTransaction.execute(access, origin, patches);
    }

    /** Apply against an in-memory access (unit tests). */
    public WorldPatchTransaction.Result applyConfirmedForTest(
            BlockMutationAccess access,
            BlockPos origin,
            boolean assumeContextValid
    ) {
        if (!executable() || !assumeContextValid) {
            return WorldPatchTransaction.Result.preflightRejected(null);
        }
        return WorldPatchTransaction.execute(access, origin, patches);
    }

    public static String worldKeyOf(ServerWorld world) {
        if (world == null || world.getRegistryKey() == null) {
            return "unknown";
        }
        return world.getRegistryKey().getValue().toString();
    }

    public static PalacePreviewPlan fromGenerateResult(
            TieredMountainPalaceBuilder.PalaceGenerateResult result,
            List<BlockPatch> patches,
            PalaceBuildContextFingerprint context
    ) {
        List<TieredMountainPalaceBuilder.BuildDiagnostic> diags = new ArrayList<>();
        if (result != null && result.diagnostics() != null) {
            diags.addAll(result.diagnostics());
        }
        TieredMountainPalaceBuilder.GenerateStatus st = result == null
                ? TieredMountainPalaceBuilder.GenerateStatus.FAILED
                : result.status();
        return new PalacePreviewPlan(st, patches, context, diags);
    }
}
