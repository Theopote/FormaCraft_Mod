package com.formacraft.server.terrain;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.server.build.BuildReportContext;
import com.formacraft.server.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * TerrainFit:
 * Local, per-building terrain adaptation utilities.
 *
 * Philosophy:
 * - Default to "顺地而建": do not flatten a whole area.
 * - Each unit may choose a local placement Y based on its own footprint sampling.
 *
 * v1:
 * - Snap origin Y towards median terrain height within footprint bounds (clamped delta).
 */
public final class TerrainFit {
    private TerrainFit() {}

    public record FootprintAnalysis(int minY, int maxY, int medianY, int range) {}

    public static FootprintAnalysis analyze(ServerWorld world, BlockPos center, int width, int depth) {
        if (world == null || center == null) return new FootprintAnalysis(64, 64, 64, 0);
        int w = Math.max(3, width);
        int d = Math.max(3, depth);
        int halfW = w / 2;
        int halfD = d / 2;
        BlockPos min = new BlockPos(center.getX() - halfW, 0, center.getZ() - halfD);
        BlockPos max = new BlockPos(center.getX() + halfW, 0, center.getZ() + halfD);

        List<Integer> heights = TerrainSampling.sampleHeights(world, min, max);
        if (heights.isEmpty()) return new FootprintAnalysis(center.getY(), center.getY(), center.getY(), 0);

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int y : heights) {
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        int median = TerrainSampling.medianHeight(heights);
        return new FootprintAnalysis(minY, maxY, median, Math.max(0, maxY - minY));
    }

    public static int averageFootprintHeight(ServerWorld world, BlockPos center, int width, int depth) {
        if (world == null || center == null) return 64;
        int w = Math.max(3, width);
        int d = Math.max(3, depth);
        int halfW = w / 2;
        int halfD = d / 2;
        BlockPos min = new BlockPos(center.getX() - halfW, 0, center.getZ() - halfD);
        BlockPos max = new BlockPos(center.getX() + halfW, 0, center.getZ() + halfD);
        List<Integer> heights = TerrainSampling.sampleHeights(world, min, max);
        return TerrainSampling.averageHeight(heights);
    }

    /**
     * Compute a snapped origin for a building footprint.
     * Uses average terrain height + 1 (place on top of ground), then clamps delta to avoid extreme moves.
     */
    public static BlockPos snapOrigin(ServerWorld world, BlockPos origin, int width, int depth) {
        FootprintAnalysis a = analyze(world, origin, width, depth);
        // "依地形调整"默认：平均高度更符合群体建筑/道路的整体观感（不会被极端点位拉偏太多）
        int desired = (a.minY == a.maxY) ? (a.medianY + 1) : (averageFootprintHeight(world, origin, width, depth) + 1);

        int dy = desired - origin.getY();
        // Conservative clamp: avoid burying buildings too deep; allow lifting a bit more.
        int clamped = Math.max(-4, Math.min(8, dy));
        if (clamped == 0) return origin;
        BuildReportContext.addTerrainSnapDy(clamped);
        return origin.add(0, clamped, 0);
    }

    public static BlockPos snapOrigin(ServerWorld world, BlockPos origin, BuildingSpec spec) {
        if (spec == null || spec.getFootprint() == null) return origin;
        Footprint fp = spec.getFootprint();
        return snapOrigin(world, origin, fp.getWidth(), fp.getDepth());
    }

    /**
     * ADAPTIVE terrain pad (v1):
     * - local, small modifications within footprint:
     *   - fill air/water/lava up to targetY using fill material
     *   - clear obvious obstacles above targetY (logs/leaves/vines/plants) to keep the placement clean
     *
     * This intentionally avoids "整片平整" and only touches the unit area.
     */
    public static List<PlannedBlock> adaptivePad(ServerWorld world,
                                                 BlockPos origin,
                                                 int width,
                                                 int depth,
                                                 int targetY,
                                                 BlockState fill) {
        return adaptivePad(world, origin, width, depth, targetY, fill, 2, 6);
    }

    public static List<PlannedBlock> adaptivePad(ServerWorld world,
                                                 BlockPos origin,
                                                 int width,
                                                 int depth,
                                                 int targetY,
                                                 BlockState fill,
                                                 int padDepth,
                                                 int clearHeight) {
        return adaptivePad(world, origin, width, depth, targetY, fill, padDepth, clearHeight, true, true);
    }

    public static List<PlannedBlock> adaptivePad(ServerWorld world,
                                                 BlockPos origin,
                                                 int width,
                                                 int depth,
                                                 int targetY,
                                                 BlockState fill,
                                                 int padDepth,
                                                 int clearHeight,
                                                 boolean allowWaterEdit,
                                                 boolean allowLavaEdit) {
        if (world == null || origin == null) return List.of();
        int w = Math.max(3, width);
        int d = Math.max(3, depth);
        int halfW = w / 2;
        int halfD = d / 2;
        BlockState fillState = (fill != null) ? fill : Blocks.COBBLESTONE.getDefaultState();
        int pad = Math.max(0, Math.min(6, padDepth));
        int clear = Math.max(0, Math.min(16, clearHeight));

        List<PlannedBlock> out = new ArrayList<>(Math.max(256, w * d * 6));
        int fillCount = 0;
        int clearCount = 0;

        // Fill a shallow pad (targetY-2..targetY), only if currently empty/fluids.
        for (int x = origin.getX() - halfW; x <= origin.getX() + halfW; x++) {
            for (int z = origin.getZ() - halfD; z <= origin.getZ() + halfD; z++) {
                for (int y = targetY - pad; y <= targetY; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!BuildConstraintContext.allow(p)) continue;
                    BlockState cur = world.getBlockState(p);
                    boolean isAir = cur.isAir();
                    boolean isWater = cur.getBlock() == Blocks.WATER;
                    boolean isLava = cur.getBlock() == Blocks.LAVA;
                    if (isAir
                            || (allowWaterEdit && isWater)
                            || (allowLavaEdit && isLava)) {
                        out.add(new PlannedBlock(p, fillState));
                        fillCount++;
                    }
                }

                // Clear obstacles above pad (targetY+1..targetY+clearHeight)
                for (int y = targetY + 1; y <= targetY + clear; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!BuildConstraintContext.allow(p)) continue;
                    BlockState cur = world.getBlockState(p);
                    if (!cur.isAir() && isObstacle(cur)) {
                        out.add(new PlannedBlock(p, Blocks.AIR.getDefaultState()));
                        clearCount++;
                    }
                }
            }
        }

        if (fillCount > 0 || clearCount > 0) {
            BuildReportContext.addTerrainPad(fillCount, clearCount);
        }
        return out;
    }

    private static boolean isObstacle(BlockState state) {
        // keep this intentionally small and conservative; we only clear common natural blockers.
        return state.isOf(Blocks.OAK_LOG)
                || state.isOf(Blocks.OAK_LEAVES)
                || state.isOf(Blocks.SPRUCE_LOG)
                || state.isOf(Blocks.SPRUCE_LEAVES)
                || state.isOf(Blocks.BIRCH_LOG)
                || state.isOf(Blocks.BIRCH_LEAVES)
                || state.isOf(Blocks.JUNGLE_LOG)
                || state.isOf(Blocks.JUNGLE_LEAVES)
                || state.isOf(Blocks.ACACIA_LOG)
                || state.isOf(Blocks.ACACIA_LEAVES)
                || state.isOf(Blocks.DARK_OAK_LOG)
                || state.isOf(Blocks.DARK_OAK_LEAVES)
                || state.isOf(Blocks.MANGROVE_LOG)
                || state.isOf(Blocks.MANGROVE_LEAVES)
                || state.isOf(Blocks.GRASS_BLOCK)
                || state.isOf(Blocks.SHORT_GRASS)
                || state.isOf(Blocks.TALL_GRASS)
                || state.isOf(Blocks.FERN)
                || state.isOf(Blocks.LARGE_FERN)
                || state.isOf(Blocks.VINE);
    }
}


