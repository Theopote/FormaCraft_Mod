package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.ConfirmedPlanIdentity;
import com.formacraft.common.patch.WorldPatchTransaction;
import com.formacraft.common.typology.TypologyGenerationException;
import com.formacraft.common.world.BlockMutationAccess;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    public static final String GENERATOR_VERSION =
            ConfirmedPlanIdentity.GENERATOR_VERSION + "/tiered_mountain_palace";

    public enum ApplyRejectReason {
        OK,
        NOT_EXECUTABLE,
        CONTEXT_MISMATCH,
        TERRAIN_STALE,
        EMPTY_PATCHES,
        WORLD_MISSING,
        PLAN_IDENTITY_MISMATCH,
        WORLD_CONFLICT
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
    private final Map<String, Object> paramsSnapshot;
    private final String paletteId;
    private final int corridorMx;
    private final int pathStartZ;
    private final int padY;
    private final int maxDrop;
    private final PalaceCirculationContract circulation;
    private final String patchHash;
    private final String planId;

    public PalacePreviewPlan(
            TieredMountainPalaceBuilder.GenerateStatus status,
            List<BlockPatch> patches,
            PalaceBuildContextFingerprint context,
            List<TieredMountainPalaceBuilder.BuildDiagnostic> diagnostics
    ) {
        this(status, patches, context, diagnostics, Map.of(), null,
                0, TieredMountainPalaceBuilder.MAX_APPROACH_DEPTH,
                4, TieredMountainPalaceBuilder.MAX_APPROACH_DROP, null);
    }

    public PalacePreviewPlan(
            TieredMountainPalaceBuilder.GenerateStatus status,
            List<BlockPatch> patches,
            PalaceBuildContextFingerprint context,
            List<TieredMountainPalaceBuilder.BuildDiagnostic> diagnostics,
            Map<String, Object> paramsSnapshot,
            String paletteId,
            int corridorMx,
            int pathStartZ,
            int padY,
            int maxDrop
    ) {
        this(status, patches, context, diagnostics, paramsSnapshot, paletteId,
                corridorMx, pathStartZ, padY, maxDrop, null);
    }

    public PalacePreviewPlan(
            TieredMountainPalaceBuilder.GenerateStatus status,
            List<BlockPatch> patches,
            PalaceBuildContextFingerprint context,
            List<TieredMountainPalaceBuilder.BuildDiagnostic> diagnostics,
            Map<String, Object> paramsSnapshot,
            String paletteId,
            int corridorMx,
            int pathStartZ,
            int padY,
            int maxDrop,
            PalaceCirculationContract circulation
    ) {
        this.status = status == null ? TieredMountainPalaceBuilder.GenerateStatus.FAILED : status;
        this.patches = List.copyOf(ConfirmedPlanIdentity.toFinalPatches(patches));
        this.context = Objects.requireNonNull(context, "context");
        this.diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        this.paramsSnapshot = paramsSnapshot == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(paramsSnapshot));
        this.paletteId = paletteId;
        this.corridorMx = corridorMx;
        this.pathStartZ = pathStartZ;
        this.padY = padY;
        this.maxDrop = maxDrop;
        this.circulation = circulation;
        this.patchHash = ConfirmedPlanIdentity.patchHash(this.patches);
        this.planId = ConfirmedPlanIdentity.planId(
                GENERATOR_VERSION, context.contextHash(), this.patchHash);
    }

    public TieredMountainPalaceBuilder.GenerateStatus status() { return status; }
    public List<BlockPatch> patches() { return patches; }
    public PalaceBuildContextFingerprint context() { return context; }
    public List<TieredMountainPalaceBuilder.BuildDiagnostic> diagnostics() { return diagnostics; }
    public Map<String, Object> paramsSnapshot() { return paramsSnapshot; }
    public String paletteId() { return paletteId; }
    public int corridorMx() { return corridorMx; }
    public int pathStartZ() { return pathStartZ; }
    public int padY() { return padY; }
    public int maxDrop() { return maxDrop; }
    public PalaceCirculationContract circulation() { return circulation; }
    public String patchHash() { return patchHash; }
    public String planId() { return planId; }
    public String contextHash() { return context.contextHash(); }

    public boolean executable() {
        return status == TieredMountainPalaceBuilder.GenerateStatus.SUCCESS && !patches.isEmpty();
    }

    /**
     * One overwritten plan cell: same (dx,dy,dz), different action/target.
     */
    public record CellOverwrite(BlockPatch original, BlockPatch merged) {}

    /**
     * Bind accepted compile/filter patches to this frozen plan.
     * Exact hash → this; cell keys preserved → re-freeze then
     * {@link #validateMergedIntegrity} on the <em>full</em> final patch set;
     * otherwise null (caller must refuse — never silently degrade).
     */
    public PalacePreviewPlan resolveForAcceptedPatches(List<BlockPatch> acceptedFinal) {
        if (!executable() || acceptedFinal == null || acceptedFinal.isEmpty()) {
            return null;
        }
        String acceptedHash = ConfirmedPlanIdentity.patchHash(acceptedFinal);
        if (acceptedHash.equals(patchHash)) {
            return this;
        }
        if (!cellsPreservedIn(acceptedFinal)) {
            return null;
        }
        PalacePreviewPlan merged = freezeMerged(acceptedFinal);
        if (merged == null) {
            return null;
        }
        List<TieredMountainPalaceBuilder.BuildDiagnostic> issues =
                merged.validateMergedIntegrity(this);
        if (!issues.isEmpty()) {
            return null;
        }
        return merged;
    }

    /**
     * Re-freeze a legally merged compile result: new patches, same terrain/context/params.
     * Returns null if this plan is not executable or {@code mergedPatches} is empty.
     * Produces a new {@code planId}/{@code patchHash}; {@code contextHash} is preserved.
     * Callers must run {@link #validateMergedIntegrity} before issuing a ticket.
     */
    public PalacePreviewPlan freezeMerged(List<BlockPatch> mergedPatches) {
        if (!executable() || mergedPatches == null || mergedPatches.isEmpty()) {
            return null;
        }
        List<BlockPatch> finalMerged = ConfirmedPlanIdentity.toFinalPatches(mergedPatches);
        if (finalMerged.isEmpty()) {
            return null;
        }
        // Preserve Builder-emitted circulation anchors across re-freeze.
        return new PalacePreviewPlan(
                status,
                finalMerged,
                context,
                diagnostics,
                paramsSnapshot,
                paletteId,
                corridorMx,
                pathStartZ,
                padY,
                maxDrop,
                circulation);
    }

    /**
     * True when every cell in this plan appears in {@code candidate} (final-set keys).
     * Missing cells mean integrity failure. Target changes are reported via
     * {@link #planCellOverwrites} and validated by {@link #validateMergedIntegrity}.
     */
    public boolean cellsPreservedIn(List<BlockPatch> candidate) {
        List<BlockPatch> planFinal = this.patches;
        List<BlockPatch> candFinal = ConfirmedPlanIdentity.toFinalPatches(candidate);
        if (planFinal.isEmpty()) return false;
        java.util.HashSet<Long> candKeys = new java.util.HashSet<>(candFinal.size() * 2);
        for (BlockPatch p : candFinal) {
            candKeys.add(packCell(p.dx(), p.dy(), p.dz()));
        }
        for (BlockPatch p : planFinal) {
            if (!candKeys.contains(packCell(p.dx(), p.dy(), p.dz()))) {
                return false;
            }
        }
        return true;
    }

    /** Plan cells whose action/target changed in {@code candidate}. */
    public List<CellOverwrite> planCellOverwrites(List<BlockPatch> candidate) {
        Map<Long, BlockPatch> candByCell = indexByCell(ConfirmedPlanIdentity.toFinalPatches(candidate));
        List<CellOverwrite> out = new ArrayList<>();
        for (BlockPatch orig : this.patches) {
            BlockPatch merged = candByCell.get(packCell(orig.dx(), orig.dy(), orig.dz()));
            if (merged == null) continue;
            if (!samePatchIdentity(orig, merged)) {
                out.add(new CellOverwrite(orig, merged));
            }
        }
        return List.copyOf(out);
    }

    /**
     * Final-structure gate for a re-frozen merge against {@code baseline}.
     * Uses {@link PalaceCirculationContract} from the baseline Builder emit when present.
     */
    public List<TieredMountainPalaceBuilder.BuildDiagnostic> validateMergedIntegrity(
            PalacePreviewPlan baseline
    ) {
        List<TieredMountainPalaceBuilder.BuildDiagnostic> issues = new ArrayList<>();
        if (baseline == null || !executable()) {
            issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "MERGE_BASELINE", "missing_baseline_or_not_executable"));
            return issues;
        }

        List<CellOverwrite> overwrites = baseline.planCellOverwrites(this.patches);
        for (CellOverwrite ow : overwrites) {
            if (isStructuralProtected(ow.original()) && !samePatchIdentity(ow.original(), ow.merged())) {
                if (isDoorPatch(ow.original()) && !isDoorPatch(ow.merged())) {
                    issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                            "DOOR_OVERWRITE",
                            "cell=" + ow.original().dx() + "," + ow.original().dy() + "," + ow.original().dz()));
                }
                if (isStairPatch(ow.original()) && !isStairPatch(ow.merged())) {
                    issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                            "STAIR_OVERWRITE",
                            "cell=" + ow.original().dx() + "," + ow.original().dy() + "," + ow.original().dz()));
                }
                if (isClearancePatch(ow.original()) && !isClearancePatch(ow.merged())) {
                    issues.add(new TieredMountainPalaceBuilder.BuildDiagnostic(
                            "CLEARANCE_OVERWRITE",
                            "cell=" + ow.original().dx() + "," + ow.original().dy() + "," + ow.original().dz()));
                }
            }
        }
        if (!issues.isEmpty()) {
            return issues;
        }

        // Full shared circulation check on the final merged patch set.
        issues.addAll(validateFinalVoxelCirculation());
        return issues;
    }

    /**
     * Voxel circulation over this plan's full patch set using Builder-emitted anchors
     * when available ({@link #circulation}).
     */
    public List<TieredMountainPalaceBuilder.BuildDiagnostic> validateFinalVoxelCirculation() {
        PalaceCirculationContract contract = this.circulation;
        if (contract == null || !contract.hasAnchors()) {
            return List.of(new TieredMountainPalaceBuilder.BuildDiagnostic(
                    "CIRCULATION_CONTRACT_MISSING",
                    "plan_lacks_builder_anchors"));
        }
        return PalaceCirculationValidator.validate(patches, contract);
    }

    public static boolean isStructuralProtected(BlockPatch p) {
        return PalaceCirculationValidator.isStructuralProtected(p);
    }

    public static boolean isDoorPatch(BlockPatch p) {
        return PalaceCirculationValidator.isDoorPatch(p);
    }

    public static boolean isStairPatch(BlockPatch p) {
        return PalaceCirculationValidator.isStairPatch(p);
    }

    public static boolean isClearancePatch(BlockPatch p) {
        return PalaceCirculationValidator.isClearancePatch(p);
    }

    private static Map<Long, BlockPatch> indexByCell(List<BlockPatch> list) {
        Map<Long, BlockPatch> map = new HashMap<>();
        if (list == null) return map;
        for (BlockPatch p : list) {
            if (p == null) continue;
            map.put(packCell(p.dx(), p.dy(), p.dz()), p);
        }
        return map;
    }

    private static boolean samePatchIdentity(BlockPatch a, BlockPatch b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        String aa = a.action() == null ? "" : a.action().toLowerCase(Locale.ROOT);
        String ba = b.action() == null ? "" : b.action().toLowerCase(Locale.ROOT);
        String at = a.targetBlock() == null ? "" : a.targetBlock().toLowerCase(Locale.ROOT);
        String bt = b.targetBlock() == null ? "" : b.targetBlock().toLowerCase(Locale.ROOT);
        return aa.equals(ba) && at.equals(bt);
    }

    private static long packCell(int dx, int dy, int dz) {
        return (((long) dx & 0x1FFFFFL) << 42)
                | (((long) dy & 0x1FFFFFL) << 21)
                | ((long) dz & 0x1FFFFFL);
    }

    /**
     * Re-sample approach terrain + rebuild context using the frozen params snapshot.
     * Used by formal Confirm — complementary to expectedStates cell checks.
     */
    public ApplyValidation validateLiveTerrainAndContext(ServerWorld world) {
        return validateForApply(
                world,
                context.origin(),
                context.facing(),
                paramsSnapshot,
                paletteId,
                corridorMx,
                pathStartZ,
                padY,
                maxDrop,
                TieredMountainPalaceBuilder::localToWorldPublic
        );
    }

    public ApplyValidation validateForApply(
            ServerWorld world,
            BlockPos origin,
            Direction facing,
            Map<String, Object> currentParams,
            String paletteIdArg,
            int mx,
            int pathStartZArg,
            int padYArg,
            int maxDropArg,
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
        String livePatchHash = ConfirmedPlanIdentity.patchHash(patches);
        if (!livePatchHash.equals(patchHash)) {
            return new ApplyValidation(ApplyRejectReason.PLAN_IDENTITY_MISMATCH,
                    "patchHash drifted");
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
                world, origin, face, mx, pathStartZArg, padYArg, maxDropArg, transform);
        if (!live.contentHash().equals(context.terrainContentHash())) {
            return new ApplyValidation(ApplyRejectReason.TERRAIN_STALE,
                    "live=" + live.contentHash() + "_plan=" + context.terrainContentHash());
        }
        PalaceBuildContextFingerprint liveCtx = PalaceBuildContextFingerprint.create(
                worldKey, origin, face, currentParams, live.contentHash(), paletteIdArg);
        if (!liveCtx.contextHash().equals(context.contextHash())) {
            return new ApplyValidation(ApplyRejectReason.CONTEXT_MISMATCH,
                    "liveCtx=" + liveCtx.contextHash() + "_plan=" + context.contextHash());
        }
        return new ApplyValidation(ApplyRejectReason.OK, "");
    }

    public WorldPatchTransaction.Result applyConfirmed(
            ServerWorld world,
            UUID playerId,
            BlockPos origin,
            Direction facing,
            Map<String, Object> currentParams,
            String paletteIdArg,
            int mx,
            int pathStartZArg,
            int padYArg,
            int maxDropArg,
            PalaceTerrainSnapshot.LocalTransform transform
    ) {
        ApplyValidation v = validateForApply(
                world, origin, facing, currentParams, paletteIdArg,
                mx, pathStartZArg, padYArg, maxDropArg, transform);
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
        return fromGenerateResult(result, patches, context, Map.of(), null,
                0, TieredMountainPalaceBuilder.MAX_APPROACH_DEPTH,
                4, TieredMountainPalaceBuilder.MAX_APPROACH_DROP);
    }

    public static PalacePreviewPlan fromGenerateResult(
            TieredMountainPalaceBuilder.PalaceGenerateResult result,
            List<BlockPatch> patches,
            PalaceBuildContextFingerprint context,
            Map<String, Object> paramsSnapshot,
            String paletteId,
            int corridorMx,
            int pathStartZ,
            int padY,
            int maxDrop
    ) {
        return fromGenerateResult(result, patches, context, paramsSnapshot, paletteId,
                corridorMx, pathStartZ, padY, maxDrop,
                result == null ? null : result.circulation());
    }

    public static PalacePreviewPlan fromGenerateResult(
            TieredMountainPalaceBuilder.PalaceGenerateResult result,
            List<BlockPatch> patches,
            PalaceBuildContextFingerprint context,
            Map<String, Object> paramsSnapshot,
            String paletteId,
            int corridorMx,
            int pathStartZ,
            int padY,
            int maxDrop,
            PalaceCirculationContract circulation
    ) {
        List<TieredMountainPalaceBuilder.BuildDiagnostic> diags = new ArrayList<>();
        if (result != null && result.diagnostics() != null) {
            diags.addAll(result.diagnostics());
        }
        TieredMountainPalaceBuilder.GenerateStatus st = result == null
                ? TieredMountainPalaceBuilder.GenerateStatus.FAILED
                : result.status();
        PalaceCirculationContract contract = circulation != null
                ? circulation
                : (result == null ? null : result.circulation());
        return new PalacePreviewPlan(st, patches, context, diags,
                paramsSnapshot, paletteId, corridorMx, pathStartZ, padY, maxDrop, contract);
    }
}
