package com.formacraft.server.cluster.layout;

import net.minecraft.util.math.BlockPos;

import java.util.Random;

/**
 * BuildArea: a simple rectangular placement region around an anchor.
 * v1 is rectangle-only; outline/selection are enforced via BuildConstraintContext by TerrainFields.
 */
public class BuildArea {
    public final int halfX;
    public final int halfZ;

    public BuildArea(int halfX, int halfZ) {
        this.halfX = Math.max(8, halfX);
        this.halfZ = Math.max(8, halfZ);
    }

    public BlockPos randomRelOrigin(Random rng) {
        int dx = rng.nextInt(halfX * 2 + 1) - halfX;
        int dz = rng.nextInt(halfZ * 2 + 1) - halfZ;
        return new BlockPos(dx, 0, dz);
    }

    public boolean canFitRect(BlockPos relOrigin, int width, int depth) {
        int x0 = relOrigin.getX();
        int z0 = relOrigin.getZ();
        int x1 = x0 + Math.max(1, width);
        int z1 = z0 + Math.max(1, depth);
        return x0 >= -halfX && z0 >= -halfZ && x1 <= halfX && z1 <= halfZ;
    }

    public double normalizedDistanceToCenter(BlockPos relOrigin) {
        double nx = Math.abs(relOrigin.getX()) / (double) Math.max(1, halfX);
        double nz = Math.abs(relOrigin.getZ()) / (double) Math.max(1, halfZ);
        return Math.min(1.0, Math.sqrt(nx * nx + nz * nz));
    }
}


