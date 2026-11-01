package com.formacraft.server.preview;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.build.quality.BuildQualityReport;
import com.formacraft.server.build.quality.GradedQualityChecker;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 预览平移与位置相关质量复检（不重新生成建筑，仅平移已有方块集）。
 */
public final class PreviewStructureShifter {
    public static final int MAX_SINGLE_OFFSET = 32;

    private PreviewStructureShifter() {}

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
            );
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
        );
    }

    /**
     * 对已平移结构重新跑位置相关质量检查（高度、区块、支撑/悬浮等）。
     */
    public static BuildQualityReport recheckAtWorld(GeneratedStructure structure, ServerWorld world) {
        return GradedQualityChecker.checkStructure(structure, null, world, false);
    }
}
