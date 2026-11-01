package com.formacraft.server.build;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.server.build.quality.BuildQualityReport;
import com.formacraft.server.build.quality.BuildQualitySeverity;
import com.formacraft.server.build.quality.GradedQualityChecker;
import com.formacraft.server.preview.PreviewStorage;
import com.formacraft.server.assembly.AssemblyCirculationConstraints;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Preview pipeline: initial quality → auto-repair → constraint clip →
 * collapse to final cells → circulation → final quality → deliver.
 * <p>
 * Final-state collapse runs before circulation and final quality so checks,
 * digest, outline, and Apply share one cell set.
 */
public final class BuildPreviewPipeline {
    private BuildPreviewPipeline() {}

    public record Result(
            GeneratedStructure structure,
            BuildQualityReport report,
            boolean delivered
    ) {}

    /** Last-write-wins collapse plus overwrite diagnostics. */
    public record CollapseStats(
            List<PlannedBlock> blocks,
            int duplicatePositions,
            int conflictingOverwrites
    ) {}

    public static Result prepare(
            ServerPlayerEntity player,
            ServerWorld world,
            GeneratedStructure generated,
            BuildingSpec spec,
            FormaRequest req,
            Optional<BuildingStyle> style
    ) {
        return prepare(player, world, generated, spec, req, style, List.of());
    }

    public static Result prepare(ServerPlayerEntity player, ServerWorld world, GeneratedStructure generated,
                                 BuildingSpec spec, FormaRequest req, Optional<BuildingStyle> style,
                                 List<AssemblyCirculationConstraints.Flight> circulation) {
        return prepare(player, world, generated, spec, req, style, circulation, false);
    }

    public static Result prepare(ServerPlayerEntity player, ServerWorld world, GeneratedStructure generated,
                                 BuildingSpec spec, FormaRequest req, Optional<BuildingStyle> style,
                                 List<AssemblyCirculationConstraints.Flight> circulation, boolean landingPrepared) {
        BuildQualityReport report = GradedQualityChecker.checkStructure(generated, spec, world, landingPrepared);
        tagPhase(report, "initial");
        report.logIssues((generated != null ? generated.getDescription() : "structure") + " (initial)");

        if (!report.allowPreview()) {
            storeReport(player, report);
            return new Result(null, report, false);
        }

        List<PlannedBlock> source = generated != null ? generated.getBlocks() : List.of();
        BuildAutoRepair.Result repair = BuildConstraintContext.withRequest(req, () ->
                BuildAutoRepair.apply(world, style, source, !landingPrepared && allowsAutomaticSupports(req))
        );

        mergeRepair(report, repair);

        List<PlannedBlock> clipped = BuildConstraintClipper.clipPlannedBlocks(repair.blocks(), req);
        int clippedCount = Math.max(0, repair.blocks().size() - clipped.size());
        if (clippedCount > 0) {
            report.stats().clippedByConstraint += clippedCount;
            report.add(BuildQualitySeverity.INFO, "CLIPPED",
                    "自动裁剪 " + clippedCount + " 个越界方块");
        }

        if (clipped.isEmpty()) {
            report.add(BuildQualitySeverity.FATAL, "STRUCT_EMPTY_AFTER_CLIP",
                    "裁剪后没有任何方块，无法预览");
            storeReport(player, report);
            return new Result(null, report, false);
        }

        // Normalize before circulation / final QC so checks match delivered preview.
        CollapseStats collapse = collapseToFinalCellsWithStats(clipped);
        List<PlannedBlock> finalCells = collapse.blocks();
        report.stats().duplicatePositions += collapse.duplicatePositions();
        report.stats().conflictingOverwrites += collapse.conflictingOverwrites();
        if (collapse.conflictingOverwrites() > 0) {
            report.add(BuildQualitySeverity.WARNING, "CONFLICTING_OVERWRITES",
                    "同坐标不同方块覆盖 " + collapse.conflictingOverwrites()
                            + " 处（已按最后写入交付；请检查上游构件冲突）");
        }
        if (finalCells.isEmpty()) {
            report.add(BuildQualitySeverity.FATAL, "STRUCT_EMPTY_AFTER_COLLAPSE",
                    "归一化后没有任何方块，无法预览");
            storeReport(player, report);
            return new Result(null, report, false);
        }

        report.stats().totalBlocks = countSolid(finalCells);

        if (!validateCirculation(finalCells, circulation, report)) {
            storeReport(player, report);
            return new Result(null, report, false);
        }

        GeneratedStructure structure = null;
        if (generated != null) {
            structure = new GeneratedStructure(
                    player.getUuid(),
                    generated.getOrigin(),
                    generated.getDescription(),
                    finalCells
            );
        }

        // Final QC on the same cells that will be stored / outlined / applied.
        BuildQualityReport finalReport = GradedQualityChecker.checkStructure(
                structure, spec, world, landingPrepared);
        report = finalizedReport(report, finalReport);
        escalateResidualFloating(report);
        report.logIssues((generated != null ? generated.getDescription() : "structure") + " (final preview)");
        if (!report.allowPreview()) {
            report.add(BuildQualitySeverity.FATAL, "E_FINAL_QUALITY_FAILED",
                    "自动修复或裁剪后最终质量检查未通过，已拒绝预览");
            storeReport(player, report);
            return new Result(null, report, false);
        }

        storeReport(player, report);
        return new Result(structure, report, true);
    }

    /**
     * Last-write-wins per world position, stable first-seen iteration order.
     * Aligns delivered BUILD preview with {@link com.formacraft.common.patch.ConfirmedPlanIdentity}
     * and {@link com.formacraft.server.preview.PreviewStorage#digestStructure}.
     */
    static List<PlannedBlock> collapseToFinalCells(List<PlannedBlock> blocks) {
        return collapseToFinalCellsWithStats(blocks).blocks();
    }

    static CollapseStats collapseToFinalCellsWithStats(List<PlannedBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return new CollapseStats(List.of(), 0, 0);
        }
        LinkedHashMap<BlockPos, PlannedBlock> byPos = new LinkedHashMap<>();
        int duplicates = 0;
        int conflicts = 0;
        for (PlannedBlock block : blocks) {
            if (block == null || block.getPos() == null) continue;
            BlockPos key = block.getPos().toImmutable();
            PlannedBlock previous = byPos.put(key, block);
            if (previous == null) continue;
            duplicates++;
            if (!Objects.equals(stateKey(previous), stateKey(block))) {
                conflicts++;
            }
        }
        return new CollapseStats(List.copyOf(byPos.values()), duplicates, conflicts);
    }

    private static String stateKey(PlannedBlock block) {
        return block.getTargetState() == null ? "null" : block.getTargetState().toString();
    }

    static boolean allowsAutomaticSupports(FormaRequest req) {
        if (req == null) return true;
        String text = req.getUserMessage();
        if (text == null || text.isBlank()) return true;
        text = text.replaceAll("\\s+", "");
        return !java.util.regex.Pattern.compile(
                "(?:不要|不用|不需要|禁止).{0,24}(?:额外支撑|支撑柱|自动支撑|补支撑)"
                        + "|(?:no|without)\\s*(?:extra|additional|automatic)\\s*supports?",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    static BuildQualityReport finalizedReport(BuildQualityReport initial, BuildQualityReport delivered) {
        if (initial.hasFatal()) return initial;
        delivered.phase("final");
        // Initial findings describe the input, not the repaired preview's apply permission.
        for (var issue : initial.issues())
            delivered.add(issue.asHistory());
        var from = initial.stats();
        var to = delivered.stats();
        to.repairedColumns = from.repairedColumns;
        to.supportBlocksAdded = from.supportBlocksAdded;
        to.clippedByConstraint = from.clippedByConstraint;
        to.rejectedPatches = from.rejectedPatches;
        to.duplicatePositions = Math.max(to.duplicatePositions, from.duplicatePositions);
        to.conflictingOverwrites = Math.max(to.conflictingOverwrites, from.conflictingOverwrites);
        return delivered;
    }

    /**
     * After auto-repair, residual floating above the hard gate is FATAL (force cannot bypass).
     * Initial-phase FLOATING stays ERROR so repair still runs.
     */
    public static void escalateResidualFloating(BuildQualityReport report) {
        if (report == null) return;
        int floating = report.stats().floatingColumns;
        if (floating < BuildQualityReport.floatingFatalColumns()) return;
        report.add(BuildQualitySeverity.FATAL, "FLOATING_RESIDUAL",
                "仍有 " + floating + " 个悬空柱未修复，已拒绝预览（不可 force 绕过）");
    }

    static boolean validateCirculation(List<PlannedBlock> blocks,
                                      List<AssemblyCirculationConstraints.Flight> circulation,
                                      BuildQualityReport report) {
        try {
            AssemblyCirculationConstraints.validate(blocks, circulation);
            return true;
        } catch (AssemblyCirculationConstraints.Conflict conflict) {
            report.add(BuildQualitySeverity.FATAL, "E_PREVIEW_CIRCULATION_CONFLICT",
                    "楼梯踏面、支撑或净空未保留，请检查地形修复与选区范围。" + conflict.getMessage());
            return false;
        }
    }

    private static void mergeRepair(BuildQualityReport report, BuildAutoRepair.Result repair) {
        if (repair == null) return;
        report.stats().repairedColumns += repair.columnsFixed();
        report.stats().supportBlocksAdded += repair.supportBlocksAdded();
        if (repair.columnsFixed() > 0) {
            report.add(BuildQualitySeverity.INFO, "REPAIR_FLOAT",
                    "自动修复 " + repair.columnsFixed() + " 个悬空构件（补 " + repair.supportBlocksAdded() + " 个支撑方块）");
        }
        if (repair.summary() != null && !repair.summary().isBlank()) {
            report.add(BuildQualitySeverity.WARNING, "REPAIR_MISC", repair.summary());
        }
    }

    private static void tagPhase(BuildQualityReport report, String phase) {
        if (report == null || phase == null) return;
        report.phase(phase);
        report.add(BuildQualitySeverity.INFO, "PHASE_" + phase.toUpperCase(),
                "quality phase=" + phase);
    }

    private static int countSolid(List<PlannedBlock> blocks) {
        int n = 0;
        for (PlannedBlock pb : blocks) {
            if (pb == null || pb.getPos() == null || pb.getTargetState() == null) continue;
            if (!pb.getTargetState().isAir()) n++;
        }
        return n;
    }

    private static void storeReport(ServerPlayerEntity player, BuildQualityReport report) {
        if (player != null) {
            PreviewStorage.storeQualityReport(player, report);
        }
    }
}
