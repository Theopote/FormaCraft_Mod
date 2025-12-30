package com.formacraft.server.terrain;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.server.build.PlannedBlock;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * TerrainAdaptationEngine (v1):
 * Converts TerrainAdaptationSpec into concrete terrain operations + optional block position transforms.
 *
 * Scope (v1):
 * - FLATTEN: delegates to TerrainShaper.preprocessStructure (cut&fill + clear obstacles)
 * - ANCHOR: local pad/clear + optional deep pillars down to ground
 * - DRAPE: shifts blocks per (x,z) based on terrain delta (best for walls/paths)
 * - EMBED: carves a cavity and sinks origin
 * - FLOAT: fixed y, no terrain edit
 */
public final class TerrainAdaptationEngine {
    private TerrainAdaptationEngine() {}

    public record Bounds(BlockPos min, BlockPos max, boolean circle) {}

     private static long xzKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    public static Bounds boundsFor(BuildingSpec spec, BlockPos origin) {
        if (origin == null) return new Bounds(new BlockPos(0, 0, 0), new BlockPos(0, 0, 0), false);
        int height = (spec != null && spec.getHeight() > 0) ? spec.getHeight() : 8;

        Footprint fp = spec != null ? spec.getFootprint() : null;
        if (fp != null && "circle".equalsIgnoreCase(fp.getShape()) && fp.getRadius() > 0) {
            int r = fp.getRadius();
            BlockPos min = origin.add(-r, 0, -r);
            BlockPos max = origin.add(r, height, r);
            return new Bounds(min, max, true);
        }

        int w = 8;
        int d = 6;
        if (fp != null) {
            if (fp.getWidth() > 0) w = fp.getWidth();
            if (fp.getDepth() > 0) d = fp.getDepth();
        }
        // legacy single-build convention: rectangle origin is min corner; city generators often treat origin as center.
        BlockPos min = origin;
        BlockPos max = origin.add(Math.max(1, w), height, Math.max(1, d));
        return new Bounds(min, max, false);
    }

    /** For city/district placement: interpret origin as footprint center. */
    public static Bounds boundsForCenteredFootprint(BuildingSpec spec, BlockPos center) {
        if (center == null) return new Bounds(new BlockPos(0, 0, 0), new BlockPos(0, 0, 0), false);
        int height = (spec != null && spec.getHeight() > 0) ? spec.getHeight() : 8;

        Footprint fp = spec != null ? spec.getFootprint() : null;
        if (fp != null && "circle".equalsIgnoreCase(fp.getShape()) && fp.getRadius() > 0) {
            int r = fp.getRadius();
            BlockPos min = center.add(-r, 0, -r);
            BlockPos max = center.add(r, height, r);
            return new Bounds(min, max, true);
        }

        int w = 8;
        int d = 6;
        if (fp != null) {
            if (fp.getWidth() > 0) w = fp.getWidth();
            if (fp.getDepth() > 0) d = fp.getDepth();
        }
        int halfW = Math.max(1, w) / 2;
        int halfD = Math.max(1, d) / 2;
        BlockPos min = center.add(-halfW, 0, -halfD);
        BlockPos max = center.add(halfW, height, halfD);
        return new Bounds(min, max, false);
    }

    public static int computeBaseY(ServerWorld world, Bounds b, TerrainAdaptationSpec spec, int fallbackY) {
        if (world == null || b == null) return fallbackY;
        TerrainBaseLevel bl = spec != null ? spec.baseLevel() : TerrainBaseLevel.MEDIAN;
        if (bl == TerrainBaseLevel.FIXED && spec != null && spec.fixedY() != null) return spec.fixedY();

        List<Integer> heights = TerrainSampling.sampleHeights(world, new BlockPos(b.min.getX(), 0, b.min.getZ()), new BlockPos(b.max.getX(), 0, b.max.getZ()));
        if (heights.isEmpty()) return fallbackY;

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long sum = 0L;
        for (int h : heights) {
            min = Math.min(min, h);
            max = Math.max(max, h);
            sum += h;
        }
        return switch (bl) {
            case LOWEST -> min;
            case HIGHEST -> max;
            case AVERAGE -> (int) Math.round(sum / (double) heights.size());
            case MODE -> TerrainSampling.modeHeight(heights);
            case MEDIAN, FIXED -> TerrainSampling.medianHeight(heights);
        };
    }

    /**
     * ANCHOR deep pillars: fill down from yStart to first solid block (or max depth).
     * Intended for cliff buildings / stilted platforms.
     */
    public static List<PlannedBlock> anchorPillars(ServerWorld world,
                                                   Bounds b,
                                                   int yStart,
                                                   BlockState material,
                                                   int maxDepth,
                                                   boolean allowWater,
                                                   boolean allowLava) {
        if (world == null || b == null) return List.of();
        BlockState fill = material != null ? material : Blocks.COBBLESTONE.getDefaultState();
        int md = Math.max(1, Math.min(256, maxDepth));

        int w = Math.max(1, b.max.getX() - b.min.getX() + 1);
        int d = Math.max(1, b.max.getZ() - b.min.getZ() + 1);
        int step = (w * d >= 65 * 65) ? 2 : 1; // keep worst-case sane

        List<PlannedBlock> out = new ArrayList<>(Math.max(256, (w * d) * 6));
        for (int x = b.min.getX(); x <= b.max.getX(); x += step) {
            for (int z = b.min.getZ(); z <= b.max.getZ(); z += step) {
                if (b.circle) {
                    int cx = (b.min.getX() + b.max.getX()) / 2;
                    int cz = (b.min.getZ() + b.max.getZ()) / 2;
                    int r = Math.max(1, (b.max.getX() - b.min.getX()) / 2);
                    int dx = x - cx;
                    int dz = z - cz;
                    if (dx * dx + dz * dz > r * r) continue;
                }

                // raycast down
                int y = yStart - 1;
                int depth = 0;
                while (y > world.getBottomY() && depth < md) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!BuildConstraintContext.allow(p)) break;
                    BlockState cur = world.getBlockState(p);
                    boolean isAir = cur.isAir();
                    boolean isWater = cur.getBlock() == Blocks.WATER;
                    boolean isLava = cur.getBlock() == Blocks.LAVA;

                    if (!isAir && !(allowWater && isWater) && !(allowLava && isLava)) {
                        break; // hit solid
                    }
                    out.add(new PlannedBlock(p, fill));
                    y--;
                    depth++;
                }
            }
        }
        return out;
    }

    /**
     * DRAPE transform: shift blocks vertically per (x,z) column.
     * v1.1:
     * - builds a height field within bounds (or derives from touched columns)
     * - optionally smooths to respect maxStepHeight between neighbors
     * - then shifts each block by the (x,z) column delta from baseY
     */
    public static List<PlannedBlock> drape(ServerWorld world,
                                          List<PlannedBlock> blocks,
                                          int baseY,
                                          int maxStepHeight,
                                          Bounds bounds) {
        if (world == null || blocks == null || blocks.isEmpty()) return blocks;
        int maxStep = Math.max(0, Math.min(8, maxStepHeight));

        // Precompute dy field (smoothed) if a reasonable bounds is provided.
        Long2IntOpenHashMap dyByXZ = new Long2IntOpenHashMap();
        dyByXZ.defaultReturnValue(Integer.MIN_VALUE);
        if (bounds != null) {
            int w = Math.max(1, bounds.max.getX() - bounds.min.getX() + 1);
            int d = Math.max(1, bounds.max.getZ() - bounds.min.getZ() + 1);
            // keep the cost bounded; if too large, we fall back to per-column sampling.
            if ((long) w * (long) d <= 128L * 128L) {
                int[][] top = new int[w][d];
                for (int ix = 0; ix < w; ix++) {
                    int x = bounds.min.getX() + ix;
                    for (int iz = 0; iz < d; iz++) {
                        int z = bounds.min.getZ() + iz;
                        if (bounds.circle) {
                            int cx = (bounds.min.getX() + bounds.max.getX()) / 2;
                            int cz = (bounds.min.getZ() + bounds.max.getZ()) / 2;
                            int r = Math.max(1, (bounds.max.getX() - bounds.min.getX()) / 2);
                            int dx = x - cx;
                            int dz = z - cz;
                            if (dx * dx + dz * dz > r * r) {
                                top[ix][iz] = baseY;
                                continue;
                            }
                        }
                        top[ix][iz] = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                    }
                }

                // Smooth: iterative relaxation to enforce neighbor step constraints.
                if (maxStep > 0) {
                    int iters = 6;
                    for (int it = 0; it < iters; it++) {
                        boolean changed = false;
                        for (int ix = 0; ix < w; ix++) {
                            for (int iz = 0; iz < d; iz++) {
                                int v = top[ix][iz];
                                int best = v;
                                if (ix > 0) best = clampToNeighbor(best, top[ix - 1][iz], maxStep);
                                if (ix + 1 < w) best = clampToNeighbor(best, top[ix + 1][iz], maxStep);
                                if (iz > 0) best = clampToNeighbor(best, top[ix][iz - 1], maxStep);
                                if (iz + 1 < d) best = clampToNeighbor(best, top[ix][iz + 1], maxStep);
                                if (best != v) {
                                    top[ix][iz] = best;
                                    changed = true;
                                }
                            }
                        }
                        if (!changed) break;
                    }
                }

                for (int ix = 0; ix < w; ix++) {
                    int x = bounds.min.getX() + ix;
                    for (int iz = 0; iz < d; iz++) {
                        int z = bounds.min.getZ() + iz;
                        int dy = top[ix][iz] - baseY;
                        dyByXZ.put(xzKey(x, z), dy);
                    }
                }
            }
        }

        List<PlannedBlock> out = new ArrayList<>(blocks.size());
        for (PlannedBlock pb : blocks) {
            if (pb == null || pb.getPos() == null) continue;
            BlockPos p = pb.getPos();
            long key = xzKey(p.getX(), p.getZ());
            int dy = dyByXZ.get(key);
            if (dy == Integer.MIN_VALUE) {
                int top = world.getTopY(Heightmap.Type.WORLD_SURFACE, p.getX(), p.getZ());
                dy = top - baseY;
                if (maxStep > 0) dy = Math.max(-maxStep, Math.min(maxStep, dy)); // fallback clamp
                dyByXZ.put(key, dy);
            }
            out.add(new PlannedBlock(p.add(0, dy, 0), pb.getTargetState()));
        }
        return out;
    }

    private static int clampToNeighbor(int v, int n, int maxStep) {
        if (v > n + maxStep) return n + maxStep;
        if (v < n - maxStep) return n - maxStep;
        return v;
    }

    /**
     * DRAPE foundation columns:
     * For each (x,z) within bounds, fill from (terrainY - foundationDepth) .. terrainY to avoid露底.
     */
    public static List<PlannedBlock> drapeFoundationColumns(ServerWorld world,
                                                            Bounds b,
                                                            int foundationDepth,
                                                            BlockState fillMaterial,
                                                            boolean allowWater,
                                                            boolean allowLava) {
        if (world == null || b == null) return List.of();
        int fd = Math.max(0, Math.min(32, foundationDepth));
        if (fd <= 0) return List.of();
        BlockState fill = fillMaterial != null ? fillMaterial : Blocks.COBBLESTONE.getDefaultState();

        int w = Math.max(1, b.max.getX() - b.min.getX() + 1);
        int d = Math.max(1, b.max.getZ() - b.min.getZ() + 1);
        int step = (w * d >= 80 * 80) ? 2 : 1;

        List<PlannedBlock> out = new ArrayList<>(Math.max(256, w * d * Math.min(6, fd)));
        for (int x = b.min.getX(); x <= b.max.getX(); x += step) {
            for (int z = b.min.getZ(); z <= b.max.getZ(); z += step) {
                if (b.circle) {
                    int cx = (b.min.getX() + b.max.getX()) / 2;
                    int cz = (b.min.getZ() + b.max.getZ()) / 2;
                    int r = Math.max(1, (b.max.getX() - b.min.getX()) / 2);
                    int dx = x - cx;
                    int dz = z - cz;
                    if (dx * dx + dz * dz > r * r) continue;
                }
                int top = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                for (int y = top - fd; y <= top; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!BuildConstraintContext.allow(p)) continue;
                    BlockState cur = world.getBlockState(p);
                    boolean isAir = cur.isAir();
                    boolean isWater = cur.getBlock() == Blocks.WATER;
                    boolean isLava = cur.getBlock() == Blocks.LAVA;
                    if (isAir || (allowWater && isWater) || (allowLava && isLava)) {
                        out.add(new PlannedBlock(p, fill));
                    }
                }
            }
        }
        return out;
    }

    /**
     * EMBED carve: clear a cavity within the unit bounds, from (baseY..baseY+height+clearHeight).
     * This is conservative: only clears if BuildConstraintContext allows.
     */
    public static List<PlannedBlock> carve(ServerWorld world, Bounds b, int baseY, int clearHeight) {
        if (world == null || b == null) return List.of();
        int clear = Math.max(0, Math.min(32, clearHeight));
        int y0 = baseY;
        // Use a conservative world max bound (1.21 default top is 383), avoid relying on unavailable APIs.
        int worldMaxY = 383;
        int y1 = Math.min(worldMaxY, baseY + Math.max(4, (b.max.getY() - b.min.getY())) + clear);
        List<PlannedBlock> out = new ArrayList<>(Math.max(256, (b.max.getX() - b.min.getX() + 1) * (b.max.getZ() - b.min.getZ() + 1) * 4));

        for (int x = b.min.getX(); x <= b.max.getX(); x++) {
            for (int z = b.min.getZ(); z <= b.max.getZ(); z++) {
                if (b.circle) {
                    int cx = (b.min.getX() + b.max.getX()) / 2;
                    int cz = (b.min.getZ() + b.max.getZ()) / 2;
                    int r = Math.max(1, (b.max.getX() - b.min.getX()) / 2);
                    int dx = x - cx;
                    int dz = z - cz;
                    if (dx * dx + dz * dz > r * r) continue;
                }
                for (int y = y0; y <= y1; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!BuildConstraintContext.allow(p)) continue;
                    BlockState cur = world.getBlockState(p);
                    if (!cur.isAir()) out.add(new PlannedBlock(p, Blocks.AIR.getDefaultState()));
                }
            }
        }
        return out;
    }
}


