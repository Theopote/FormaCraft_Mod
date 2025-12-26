package com.formacraft.server.cluster.layout;

import net.minecraft.util.math.BlockPos;

import java.util.Random;

/**
 * AnchoredBuildArea:
 * A BuildArea variant that biases sampling and center distance around a provided center (rel to cluster origin).
 *
 * - randomRelOrigin() samples within a local window around centerRel
 * - canFitRect() enforces a global bounding box around (0,0) with globalHalfX/Z
 * - normalizedDistanceToCenter() measures distance to centerRel (not (0,0))
 */
public final class AnchoredBuildArea extends BuildArea {
    private final BlockPos centerRel;
    private final int globalHalfX;
    private final int globalHalfZ;
    private final int sampleHalfX;
    private final int sampleHalfZ;

    public AnchoredBuildArea(BlockPos centerRel, int globalHalfX, int globalHalfZ, int sampleHalfX, int sampleHalfZ) {
        super(globalHalfX, globalHalfZ);
        this.centerRel = centerRel != null ? centerRel : BlockPos.ORIGIN;
        this.globalHalfX = Math.max(8, globalHalfX);
        this.globalHalfZ = Math.max(8, globalHalfZ);
        this.sampleHalfX = Math.max(4, Math.min(this.globalHalfX, sampleHalfX));
        this.sampleHalfZ = Math.max(4, Math.min(this.globalHalfZ, sampleHalfZ));
    }

    @Override
    public BlockPos randomRelOrigin(Random rng) {
        int dx = rng.nextInt(sampleHalfX * 2 + 1) - sampleHalfX;
        int dz = rng.nextInt(sampleHalfZ * 2 + 1) - sampleHalfZ;
        int x = centerRel.getX() + dx;
        int z = centerRel.getZ() + dz;
        // clamp to global bounds
        x = Math.max(-globalHalfX, Math.min(globalHalfX, x));
        z = Math.max(-globalHalfZ, Math.min(globalHalfZ, z));
        return new BlockPos(x, 0, z);
    }

    @Override
    public boolean canFitRect(BlockPos relOrigin, int width, int depth) {
        int x0 = relOrigin.getX();
        int z0 = relOrigin.getZ();
        int x1 = x0 + Math.max(1, width);
        int z1 = z0 + Math.max(1, depth);
        return x0 >= -globalHalfX && z0 >= -globalHalfZ && x1 <= globalHalfX && z1 <= globalHalfZ;
    }

    @Override
    public double normalizedDistanceToCenter(BlockPos relOrigin) {
        double dx = relOrigin.getX() - centerRel.getX();
        double dz = relOrigin.getZ() - centerRel.getZ();
        double denom = Math.sqrt((double) Math.max(1, sampleHalfX) * (double) Math.max(1, sampleHalfX)
                + (double) Math.max(1, sampleHalfZ) * (double) Math.max(1, sampleHalfZ));
        if (denom <= 0.0) return 0.0;
        return Math.min(1.0, Math.sqrt(dx * dx + dz * dz) / denom);
    }
}


