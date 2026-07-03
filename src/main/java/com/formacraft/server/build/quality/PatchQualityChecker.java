package com.formacraft.server.build.quality;

import com.formacraft.common.world.WorldBuildBounds;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Graded quality checks for incremental {@link BlockPatch} previews. */
public final class PatchQualityChecker {
    private PatchQualityChecker() {}

    public static BuildQualityReport check(ServerWorld world, BlockPos origin, List<BlockPatch> patches) {
        BuildQualityReport report = new BuildQualityReport();

        if (patches == null || patches.isEmpty()) {
            report.add(BuildQualitySeverity.FATAL, "PATCH_EMPTY", "Patch 列表为空，无法预览");
            return report;
        }
        if (origin == null) {
            report.add(BuildQualitySeverity.FATAL, "PATCH_NO_ORIGIN", "Patch 原点无效，无法预览");
            return report;
        }

        report.stats().totalBlocks = patches.size();

        int heightViolations = 0;
        int unloadedChunks = 0;
        int illegal = 0;
        Set<Long> seenChunks = new HashSet<>();

        for (BlockPatch patch : patches) {
            if (patch == null) {
                illegal++;
                continue;
            }
            BlockPos pos = origin.add(patch.dx(), patch.dy(), patch.dz());
            String action = patch.action() == null ? "" : patch.action().toLowerCase();

            if (!BlockPatch.REMOVE.equals(action)) {
                if (patch.targetBlock() == null || patch.targetBlock().isBlank()) {
                    illegal++;
                }
            }

            if (world != null && !WorldBuildBounds.isInsideWorldHeight(world, pos)) {
                heightViolations++;
            }

            if (world != null) {
                long ck = chunkKey(pos);
                if (seenChunks.add(ck) && !WorldBuildBounds.isChunkReady(world, pos)) {
                    unloadedChunks++;
                }
            }
        }

        report.stats().worldHeightViolations = heightViolations;
        report.stats().unloadedChunkBlocks = unloadedChunks;
        report.stats().illegalBlocks = illegal;

        if (illegal > 0) {
            report.add(BuildQualitySeverity.FATAL, "PATCH_ILLEGAL",
                    "有 " + illegal + " 个 Patch 操作缺少合法目标，无法预览");
        }
        if (heightViolations > 0) {
            report.add(BuildQualitySeverity.FATAL, "PATCH_WORLD_HEIGHT",
                    "有 " + heightViolations + " 个 Patch 超出世界高度边界，无法预览");
        }
        if (unloadedChunks > 0) {
            BuildQualitySeverity sev = unloadedChunks >= 4
                    ? BuildQualitySeverity.ERROR
                    : BuildQualitySeverity.WARNING;
            report.add(sev, "PATCH_CHUNK_UNLOADED",
                    "有 " + unloadedChunks + " 个目标区块未加载");
        }

        report.add(BuildQualitySeverity.INFO, "PATCH_COUNT",
                "共 " + patches.size() + " 个 Patch 操作待预览");
        return report;
    }

    private static long chunkKey(BlockPos pos) {
        return (((long) (pos.getX() >> 4)) << 32) ^ (pos.getZ() >> 4);
    }
}
