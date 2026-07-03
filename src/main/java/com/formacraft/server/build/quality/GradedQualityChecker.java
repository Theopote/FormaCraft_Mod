package com.formacraft.server.build.quality;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.common.world.WorldBuildBounds;
import com.formacraft.server.build.BuildConstraints;
import com.formacraft.server.build.BuildConstraintContext;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Graded structure quality checker (Fatal / Error / Warning / Info).
 */
public final class GradedQualityChecker {
    private GradedQualityChecker() {}

    public static BuildQualityReport checkStructure(
            GeneratedStructure structure,
            BuildingSpec spec,
            ServerWorld world
    ) {
        BuildQualityReport report = new BuildQualityReport();

        if (structure == null) {
            report.add(BuildQualitySeverity.FATAL, "STRUCT_NULL", "生成的结构为 null");
            return report;
        }

        List<PlannedBlock> blocks = structure.getBlocks();
        if (blocks == null || blocks.isEmpty()) {
            report.add(BuildQualitySeverity.FATAL, "STRUCT_EMPTY", "生成结果为空，没有任何方块");
            return report;
        }

        report.stats().totalBlocks = countSolidBlocks(blocks);

        checkWorldBounds(blocks, world, report);
        checkChunkReadiness(blocks, world, report);
        checkIllegalBlocks(blocks, report);
        checkConstraintBounds(blocks, report);
        checkDuplicates(blocks, report);
        checkFloatingColumns(blocks, world, report);
        checkDimensions(structure, spec, blocks, report);

        if (report.stats().totalBlocks > 0) {
            report.add(BuildQualitySeverity.INFO, "BLOCK_COUNT",
                    "共计划放置 " + report.stats().totalBlocks + " 个方块");
        }

        return report;
    }

    private static int countSolidBlocks(List<PlannedBlock> blocks) {
        int n = 0;
        for (PlannedBlock block : blocks) {
            if (block == null || block.getPos() == null) continue;
            if (block.getTargetState() != null && block.getTargetState().getBlock() != Blocks.AIR) {
                n++;
            }
        }
        return n;
    }

    private static void checkWorldBounds(List<PlannedBlock> blocks, ServerWorld world, BuildQualityReport report) {
        if (world == null) return;
        int violations = 0;
        int bottom = world.getBottomY();
        int top = WorldBuildBounds.topYInclusive(world);

        for (PlannedBlock block : blocks) {
            BlockPos pos = block.getPos();
            if (pos == null) continue;
            if (block.getTargetState() == null || block.getTargetState().getBlock() == Blocks.AIR) continue;
            int y = pos.getY();
            if (y < bottom || y > top) {
                violations++;
            }
        }

        report.stats().worldHeightViolations = violations;
        if (violations > 0) {
            report.add(BuildQualitySeverity.FATAL, "WORLD_HEIGHT",
                    "有 " + violations + " 个方块超出世界高度范围（Y " + bottom + "～" + top + "），无法预览");
        }
    }

    private static void checkChunkReadiness(List<PlannedBlock> blocks, ServerWorld world, BuildQualityReport report) {
        if (world == null) return;
        int unloaded = 0;
        Set<Long> seenChunks = new HashSet<>();

        for (PlannedBlock block : blocks) {
            BlockPos pos = block.getPos();
            if (pos == null) continue;
            if (block.getTargetState() == null || block.getTargetState().getBlock() == Blocks.AIR) continue;
            long ck = chunkKey(pos);
            if (!seenChunks.add(ck)) continue;
            if (!WorldBuildBounds.isChunkReady(world, pos)) {
                unloaded++;
            }
        }

        report.stats().unloadedChunkBlocks = unloaded;
        if (unloaded > 0) {
            BuildQualitySeverity sev = unloaded >= 4
                    ? BuildQualitySeverity.ERROR
                    : BuildQualitySeverity.WARNING;
            report.add(sev, "CHUNK_UNLOADED",
                    "有 " + unloaded + " 个区块未加载，部分方块可能无法正确放置");
        }
    }

    private static long chunkKey(BlockPos pos) {
        return (((long) (pos.getX() >> 4)) << 32) ^ (pos.getZ() >> 4);
    }

    private static void checkIllegalBlocks(List<PlannedBlock> blocks, BuildQualityReport report) {
        int illegal = 0;
        for (PlannedBlock block : blocks) {
            if (block == null) continue;
            if (block.getPos() == null) {
                illegal++;
                continue;
            }
            if (block.getTargetState() == null) {
                illegal++;
            }
        }
        report.stats().illegalBlocks = illegal;
        if (illegal > 0) {
            report.add(BuildQualitySeverity.FATAL, "ILLEGAL_BLOCK",
                    "有 " + illegal + " 个方块缺少合法状态或坐标，无法预览");
        }
    }

    private static void checkConstraintBounds(List<PlannedBlock> blocks, BuildQualityReport report) {
        BuildConstraints constraints = BuildConstraintContext.current();
        if (constraints == null) return;

        int outOfBounds = 0;
        int total = 0;
        for (PlannedBlock block : blocks) {
            BlockPos pos = block.getPos();
            if (pos == null) continue;
            if (block.getTargetState() == null || block.getTargetState().getBlock() == Blocks.AIR) continue;
            total++;
            if (!constraints.allow(pos)) {
                outOfBounds++;
            }
        }
        if (outOfBounds <= 0 || total <= 0) return;

        double pct = (outOfBounds * 100.0) / total;
        if (pct > BuildQualityReport.outOfBoundsErrorPct()) {
            report.add(BuildQualitySeverity.ERROR, "CONSTRAINT_OOB",
                    String.format("有 %.1f%% (%d/%d) 的方块超出选区/轮廓/禁区", pct, outOfBounds, total));
        } else {
            report.add(BuildQualitySeverity.WARNING, "CONSTRAINT_OOB",
                    String.format("有 %.1f%% (%d/%d) 的方块超出选区/轮廓/禁区", pct, outOfBounds, total));
        }
    }

    private static void checkDuplicates(List<PlannedBlock> blocks, BuildQualityReport report) {
        Set<BlockPos> seen = new HashSet<>();
        int dup = 0;
        for (PlannedBlock block : blocks) {
            BlockPos pos = block != null ? block.getPos() : null;
            if (pos == null) continue;
            if (!seen.add(pos)) dup++;
        }
        report.stats().duplicatePositions = dup;
        if (dup > 0) {
            report.add(BuildQualitySeverity.WARNING, "DUPLICATE_POS",
                    "发现 " + dup + " 个重复坐标（将保留最后一次写入）");
        }
    }

    private static void checkFloatingColumns(List<PlannedBlock> blocks, ServerWorld world, BuildQualityReport report) {
        if (world == null || blocks.isEmpty()) return;

        Map<Long, Integer> minYByXZ = new HashMap<>();
        Map<Long, BlockPos> sampleByXZ = new HashMap<>();
        int overallMinY = Integer.MAX_VALUE;

        for (PlannedBlock block : blocks) {
            if (block == null) continue;
            BlockPos pos = block.getPos();
            if (pos == null || block.getTargetState() == null) continue;
            if (block.getTargetState().getBlock() == Blocks.AIR) continue;
            overallMinY = Math.min(overallMinY, pos.getY());
            long key = xzKey(pos.getX(), pos.getZ());
            Integer cur = minYByXZ.get(key);
            if (cur == null || pos.getY() < cur) {
                minYByXZ.put(key, pos.getY());
                sampleByXZ.put(key, pos);
            }
        }
        if (overallMinY == Integer.MAX_VALUE) return;

        int baseBandMaxY = overallMinY + 2;
        int floating = 0;

        for (Map.Entry<Long, Integer> col : minYByXZ.entrySet()) {
            int yMin = col.getValue();
            if (yMin > baseBandMaxY) continue;
            BlockPos sample = sampleByXZ.get(col.getKey());
            if (sample == null) continue;
            BlockPos below = sample.add(0, -1, 0);
            if (!hasSupportBelow(blocks, world, below)) {
                floating++;
            }
        }

        report.stats().floatingColumns = floating;
        if (floating >= BuildQualityReport.floatingErrorColumns()) {
            report.add(BuildQualitySeverity.ERROR, "FLOATING",
                    "检测到 " + floating + " 个底层悬空柱（建议先自动修复或调整生成）");
        } else if (floating >= BuildQualityReport.floatingWarningColumns()) {
            report.add(BuildQualitySeverity.WARNING, "FLOATING",
                    "检测到 " + floating + " 个底层悬空柱");
        }
    }

    private static boolean hasSupportBelow(List<PlannedBlock> blocks, ServerWorld world, BlockPos below) {
        for (PlannedBlock pb : blocks) {
            if (pb == null) continue;
            BlockPos p = pb.getPos();
            if (below.equals(p) && pb.getTargetState() != null && pb.getTargetState().getBlock() != Blocks.AIR) {
                return true;
            }
        }
        return !world.getBlockState(below).isAir();
    }

    private static long xzKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static void checkDimensions(
            GeneratedStructure structure,
            BuildingSpec spec,
            List<PlannedBlock> blocks,
            BuildQualityReport report
    ) {
        if (spec == null || blocks.isEmpty()) return;

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (PlannedBlock block : blocks) {
            BlockPos pos = block.getPos();
            if (pos == null) continue;
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
            maxY = Math.max(maxY, pos.getY());
        }

        int actualWidth = maxX - minX + 1;
        int actualHeight = maxY - minY + 1;
        int actualDepth = maxZ - minZ + 1;

        Footprint fp = spec.getFootprint();
        if (fp != null) {
            Integer expectedWidth = fp.getWidth();
            Integer expectedDepth = fp.getDepth();
            int expectedHeight = spec.getHeight();

            if (expectedWidth != null) {
                int diff = Math.abs(actualWidth - expectedWidth);
                if (diff > expectedWidth * 0.2) {
                    report.add(BuildQualitySeverity.WARNING, "DIM_WIDTH",
                            "实际宽度 " + actualWidth + " 与预期 " + expectedWidth + " 差异较大");
                }
            }
            if (expectedDepth != null) {
                int diff = Math.abs(actualDepth - expectedDepth);
                if (diff > expectedDepth * 0.2) {
                    report.add(BuildQualitySeverity.WARNING, "DIM_DEPTH",
                            "实际深度 " + actualDepth + " 与预期 " + expectedDepth + " 差异较大");
                }
            }
            int heightDiff = Math.abs(actualHeight - expectedHeight);
            if (expectedHeight > 0 && heightDiff > expectedHeight * 0.2) {
                report.add(BuildQualitySeverity.WARNING, "DIM_HEIGHT",
                        "实际高度 " + actualHeight + " 与预期 " + expectedHeight + " 差异较大");
            }
        }

        int maxDimension = 200;
        if (actualWidth > maxDimension || actualDepth > maxDimension || actualHeight > maxDimension) {
            report.add(BuildQualitySeverity.WARNING, "DIM_LARGE",
                    "建筑尺寸较大：" + actualWidth + "×" + actualHeight + "×" + actualDepth);
        }
    }
}
