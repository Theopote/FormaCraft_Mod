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

import java.util.List;
import java.util.Optional;

/**
 * Preview pipeline: graded quality check → auto-repair → constraint clip → store.
 */
public final class BuildPreviewPipeline {
    private BuildPreviewPipeline() {}

    public record Result(
            GeneratedStructure structure,
            BuildQualityReport report,
            boolean delivered
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
        report.logIssues(generated != null ? generated.getDescription() : "structure");

        if (!report.allowPreview()) {
            storeReport(player, report);
            return new Result(null, report, false);
        }

        List<PlannedBlock> source = generated != null ? generated.getBlocks() : List.of();
        BuildAutoRepair.Result repair = BuildConstraintContext.withRequest(req, () ->
                BuildAutoRepair.apply(world, style, source, !landingPrepared)
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

        report.stats().totalBlocks = countSolid(clipped);

        if (!validateCirculation(clipped, circulation, report)) {
            storeReport(player, report);
            return new Result(null, report, false);
        }

        GeneratedStructure structure = null;
        if (generated != null) {
            structure = new GeneratedStructure(
                    player.getUuid(),
                    generated.getOrigin(),
                    generated.getDescription(),
                    clipped
            );
        }

        storeReport(player, report);
        return new Result(structure, report, true);
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
