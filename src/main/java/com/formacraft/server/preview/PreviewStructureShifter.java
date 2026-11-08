package com.formacraft.server.preview;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.server.build.quality.BuildQualityGate;
import com.formacraft.server.build.quality.BuildQualityReport;
import com.formacraft.server.build.quality.BuildQualitySeverity;
import com.formacraft.server.build.quality.GradedQualityChecker;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 预览平移与位置相关质量复检（不重新生成建筑，仅平移已有方块集）。
 * <p>
 * FATAL（如超出世界高度）时拒绝移动并保留原预览；ERROR/WARNING 仍应用移动但刷新质量报告。
 * <p>
 * Recheck policy:
 * <ul>
 *   <li>Must re-run (position/world): world height, chunk readiness, floating/support, constraint bounds.</li>
 *   <li>Must reuse original {@link BuildingSpec}: dimension-vs-spec warnings ({@code DIM_*}).</li>
 *   <li>Spec-independent structural issues (illegal blocks / empty) still re-evaluated on the shifted set.</li>
 * </ul>
 */
public final class PreviewStructureShifter {
    public static final int MAX_SINGLE_OFFSET = 32;

    private PreviewStructureShifter() {}

    public record AdjustOutcome(
            boolean applied,
            GeneratedStructure structure,
            BuildQualityReport report,
            String statusMessage
    ) {}

    public static int clampOffset(int value) {
        return Math.max(-MAX_SINGLE_OFFSET, Math.min(MAX_SINGLE_OFFSET, value));
    }

    public static GeneratedStructure shift(GeneratedStructure structure, int dx, int dy, int dz) {
        if (structure == null) return null;
        BlockPos origin = structure.getOrigin();
        if (origin == null) return structure;
        BlockPos newOrigin = origin.add(dx, dy, dz);
        List<PlannedBlock> source = structure.getBlocks();
        if (source == null || source.isEmpty()) {
            return new GeneratedStructure(
                    structure.getOwner(),
                    newOrigin,
                    structure.getDescription(),
                    source == null ? List.of() : source
            ).withAssetLock(structure.getAssetLockId());
        }
        List<PlannedBlock> shifted = new ArrayList<>(source.size());
        for (PlannedBlock block : source) {
            if (block == null || block.getPos() == null) continue;
            shifted.add(new PlannedBlock(block.getPos().add(dx, dy, dz), block.getTargetState()));
        }
        return new GeneratedStructure(
                structure.getOwner(),
                newOrigin,
                structure.getDescription(),
                shifted
        ).withAssetLock(structure.getAssetLockId());
    }

    /**
     * Recheck shifted structure with the same BuildingSpec used at preview prepare.
     * Pass {@code null} only when the preview had no BuildingSpec (e.g. FBD component plans).
     */
    public static BuildQualityReport recheckAtWorld(
            GeneratedStructure structure,
            BuildingSpec spec,
            ServerWorld world
    ) {
        return GradedQualityChecker.checkStructure(structure, spec, world, false);
    }

    /** FATAL → 拒绝移动；其余允许（ERROR 时确认需 force）。 */
    public static boolean shouldRejectAdjust(BuildQualityReport report) {
        return BuildQualityGate.decide(report, false) == BuildQualityGate.Decision.DENY_FATAL;
    }

    /**
     * 评估预览平移：FATAL 时保留 {@code current}；否则返回平移后结构并附带新质量报告。
     */
    public static AdjustOutcome evaluateAdjust(
            GeneratedStructure current,
            int dx,
            int dy,
            int dz,
            ServerWorld world
    ) {
        return evaluateAdjust(current, dx, dy, dz, world, null);
    }

    public static AdjustOutcome evaluateAdjust(
            GeneratedStructure current,
            int dx,
            int dy,
            int dz,
            ServerWorld world,
            BuildingSpec spec
    ) {
        int cdx = clampOffset(dx);
        int cdy = clampOffset(dy);
        int cdz = clampOffset(dz);
        if (current == null || (cdx == 0 && cdy == 0 && cdz == 0)) {
            return new AdjustOutcome(false, current, null, null);
        }

        GeneratedStructure shifted = shift(current, cdx, cdy, cdz);
        BuildQualityReport report = recheckAtWorld(shifted, spec, world);
        String offsetNote = "dx=" + cdx + " dy=" + cdy + " dz=" + cdz;

        if (shouldRejectAdjust(report)) {
            String reason = report != null ? report.summaryZh() : "位置不可用";
            return new AdjustOutcome(
                    false,
                    current,
                    report,
                    "预览移动被拒绝：" + reason + "（已保留原位置）"
            );
        }

        String qualityNote = "";
        if (report != null && (report.hasFatal() || report.hasError()
                || report.hasSeverity(BuildQualitySeverity.WARNING))) {
            qualityNote = " | 质量：" + report.summaryZh();
        }
        return new AdjustOutcome(
                true,
                shifted,
                report,
                "预览已调整：" + offsetNote + qualityNote
        );
    }
}
