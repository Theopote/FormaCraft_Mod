package com.formacraft.server.cluster;

import com.formacraft.server.build.BuildConstraintContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * TerrainFields (v1):
 * Pre-sampled terrain grids for cluster layout decisions.
 *
 * Fields:
 * - height(x,z): ground topY (MOTION_BLOCKING_NO_LEAVES)
 * - slope(x,z): approx slope from neighbor diffs (0..)
 * - buildable(x,z): respects BuildConstraintContext (outline/selection/protected zones)
 *
 * Sampling step:
 * - step=1..4 (default 2 for speed).
 */
public final class TerrainFields {
    public final BlockPos origin;
    public final int halfX;
    public final int halfZ;
    public final int step;

    // grid dimensions
    private final int w;
    private final int d;

    // sampled fields
    private final int[][] height;
    private final float[][] slope;
    private final boolean[][] buildable;

    private TerrainFields(BlockPos origin, int halfX, int halfZ, int step,
                          int[][] height, float[][] slope, boolean[][] buildable) {
        this.origin = origin;
        this.halfX = halfX;
        this.halfZ = halfZ;
        this.step = step;
        this.height = height;
        this.slope = slope;
        this.buildable = buildable;
        this.w = height.length;
        this.d = height[0].length;
    }

    public static TerrainFields sample(ServerWorld world, BlockPos origin, int halfX, int halfZ, int step) {
        int s = Math.max(1, Math.min(4, step));
        int hx = Math.max(8, halfX);
        int hz = Math.max(8, halfZ);

        int w = (hx * 2) / s + 1;
        int d = (hz * 2) / s + 1;

        int[][] h = new int[w][d];
        float[][] sl = new float[w][d];
        boolean[][] ok = new boolean[w][d];

        for (int ix = 0; ix < w; ix++) {
            int dx = -hx + ix * s;
            int x = origin.getX() + dx;
            for (int iz = 0; iz < d; iz++) {
                int dz = -hz + iz * s;
                int z = origin.getZ() + dz;
                int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                h[ix][iz] = top;

                // buildable mask: check xz within constraints using a stable y (origin.y)
                ok[ix][iz] = BuildConstraintContext.allow(new BlockPos(x, origin.getY(), z));
            }
        }

        // slope from neighbor diffs (finite difference)
        for (int ix = 0; ix < w; ix++) {
            for (int iz = 0; iz < d; iz++) {
                int c = h[ix][iz];
                int dx1 = (ix > 0) ? h[ix - 1][iz] : c;
                int dx2 = (ix + 1 < w) ? h[ix + 1][iz] : c;
                int dz1 = (iz > 0) ? h[ix][iz - 1] : c;
                int dz2 = (iz + 1 < d) ? h[ix][iz + 1] : c;
                float gx = (dx2 - dx1) / (2.0f * s);
                float gz = (dz2 - dz1) / (2.0f * s);
                sl[ix][iz] = (float) Math.sqrt(gx * gx + gz * gz);
            }
        }

        return new TerrainFields(origin, hx, hz, s, h, sl, ok);
    }

    public int heightAtWorldXZ(int x, int z) {
        int ix = (int) Math.round((x - (origin.getX() - halfX)) / (double) step);
        int iz = (int) Math.round((z - (origin.getZ() - halfZ)) / (double) step);
        if (ix < 0) ix = 0;
        if (iz < 0) iz = 0;
        if (ix >= w) ix = w - 1;
        if (iz >= d) iz = d - 1;
        return height[ix][iz];
    }

    public float slopeAtWorldXZ(int x, int z) {
        int ix = (int) Math.round((x - (origin.getX() - halfX)) / (double) step);
        int iz = (int) Math.round((z - (origin.getZ() - halfZ)) / (double) step);
        if (ix < 0) ix = 0;
        if (iz < 0) iz = 0;
        if (ix >= w) ix = w - 1;
        if (iz >= d) iz = d - 1;
        return slope[ix][iz];
    }

    public boolean buildableAtWorldXZ(int x, int z) {
        int ix = (int) Math.round((x - (origin.getX() - halfX)) / (double) step);
        int iz = (int) Math.round((z - (origin.getZ() - halfZ)) / (double) step);
        if (ix < 0) ix = 0;
        if (iz < 0) iz = 0;
        if (ix >= w) ix = w - 1;
        if (iz >= d) iz = d - 1;
        return buildable[ix][iz];
    }

    /**
     * Sample metrics within a footprint centered at (cx,cz).
     * Uses field samples (step grid) for speed.
     */
    public FootprintMetrics footprintMetrics(ServerWorld world, int cx, int cz, int footprintW, int footprintD) {
        int w = Math.max(5, footprintW);
        int d = Math.max(5, footprintD);
        int halfW = w / 2;
        int halfD = d / 2;

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        long sum = 0;
        int n = 0;
        float slopeSum = 0;
        int buildableBad = 0;
        int waterHits = 0;

        for (int x = cx - halfW; x <= cx + halfW; x += step) {
            for (int z = cz - halfD; z <= cz + halfD; z += step) {
                int y = heightAtWorldXZ(x, z);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                sum += y;
                slopeSum += slopeAtWorldXZ(x, z);
                n++;

                if (!buildableAtWorldXZ(x, z)) buildableBad++;

                // water check at the ground surface
                if (world != null && !world.getFluidState(new BlockPos(x, y, z)).isEmpty()) waterHits++;
            }
        }
        if (n <= 0) return new FootprintMetrics(64, 64, 64, 0, 0, 0, 0, 0);
        int avg = (int) Math.round(sum / (double) n);
        int cost = 0;
        for (int x = cx - halfW; x <= cx + halfW; x += step) {
            for (int z = cz - halfD; z <= cz + halfD; z += step) {
                int y = heightAtWorldXZ(x, z);
                cost += Math.abs(y - avg);
            }
        }
        float slopeAvg = slopeSum / n;
        return new FootprintMetrics(minY, maxY, avg, Math.max(0, maxY - minY), cost, slopeAvg, buildableBad, waterHits);
    }

    /**
     * Sample metrics within a rectangular footprint defined by its min corner (x0,z0).
     * Uses field samples (step grid) for speed.
     */
    public FootprintMetrics rectMetricsFromMinCorner(ServerWorld world, int x0, int z0, int footprintW, int footprintD) {
        int w = Math.max(3, footprintW);
        int d = Math.max(3, footprintD);
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        long sum = 0;
        int n = 0;
        float slopeSum = 0;
        int buildableBad = 0;
        int waterHits = 0;

        for (int x = x0; x <= x1; x += step) {
            for (int z = z0; z <= z1; z += step) {
                int y = heightAtWorldXZ(x, z);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                sum += y;
                slopeSum += slopeAtWorldXZ(x, z);
                n++;

                if (!buildableAtWorldXZ(x, z)) buildableBad++;
                if (world != null && !world.getFluidState(new BlockPos(x, y, z)).isEmpty()) waterHits++;
            }
        }
        if (n <= 0) return new FootprintMetrics(64, 64, 64, 0, 0, 0, 0, 0);
        int avg = (int) Math.round(sum / (double) n);
        int cost = 0;
        for (int x = x0; x <= x1; x += step) {
            for (int z = z0; z <= z1; z += step) {
                int y = heightAtWorldXZ(x, z);
                cost += Math.abs(y - avg);
            }
        }
        float slopeAvg = slopeSum / n;
        return new FootprintMetrics(minY, maxY, avg, Math.max(0, maxY - minY), cost, slopeAvg, buildableBad, waterHits);
    }

    public record FootprintMetrics(int minY,
                                   int maxY,
                                   int avgY,
                                   int range,
                                   int flattenCost,
                                   float slopeAvg,
                                   int buildableBad,
                                   int waterHits) {}

    public List<BlockPos> debugSamplePoints() {
        List<BlockPos> pts = new ArrayList<>();
        for (int ix = 0; ix < w; ix++) {
            int x = origin.getX() - halfX + ix * step;
            for (int iz = 0; iz < d; iz++) {
                int z = origin.getZ() - halfZ + iz * step;
                pts.add(new BlockPos(x, origin.getY(), z));
            }
        }
        return pts;
    }
}


