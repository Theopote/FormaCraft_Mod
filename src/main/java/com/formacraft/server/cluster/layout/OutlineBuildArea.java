package com.formacraft.server.cluster.layout;

import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.server.build.BuildConstraintContext;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Random;

/**
 * OutlineBuildArea:
 * Candidate sampling directly within OutlineShape (circle/polygon) for higher efficiency/stability.
 *
 * Notes:
 * - Still respects BuildConstraintContext.allow(...) as final authority.
 * - Produces rel origins (dx,0,dz) relative to cluster origin.
 */
public final class OutlineBuildArea extends BuildArea {
    private final OutlineShape outline; // world coords
    private final BlockPos clusterOrigin; // world coords
    private final int maxTries;
    private final int sampleY;

    public OutlineBuildArea(OutlineShape outline, BlockPos clusterOrigin, int fallbackHalfX, int fallbackHalfZ) {
        super(fallbackHalfX, fallbackHalfZ);
        this.outline = outline;
        this.clusterOrigin = clusterOrigin;
        this.maxTries = 200;
        if (outline != null) {
            int y0 = clusterOrigin != null ? clusterOrigin.getY() : outline.minY();
            this.sampleY = Math.max(outline.minY(), Math.min(outline.maxY(), y0));
        } else {
            this.sampleY = clusterOrigin != null ? clusterOrigin.getY() : 64;
        }
    }

    @Override
    public BlockPos randomRelOrigin(Random rng) {
        if (outline == null || clusterOrigin == null) return super.randomRelOrigin(rng);

        // Circle sampling
        if ("circle".equalsIgnoreCase(outline.shapeType()) && outline.center() != null && outline.radius() > 1) {
            for (int i = 0; i < maxTries; i++) {
                // uniform disk: r = sqrt(u) * R
                double u = rng.nextDouble();
                double v = rng.nextDouble();
                double r = Math.sqrt(u) * outline.radius();
                double a = v * Math.PI * 2.0;
                int x = (int) Math.round(outline.center().getX() + Math.cos(a) * r);
                int z = (int) Math.round(outline.center().getZ() + Math.sin(a) * r);
                BlockPos worldP = new BlockPos(x, clusterOrigin.getY(), z);
                if (!BuildConstraintContext.allow(worldP)) continue;
                return new BlockPos(x - clusterOrigin.getX(), 0, z - clusterOrigin.getZ());
            }
            return super.randomRelOrigin(rng);
        }

        // Polygon sampling (bounding box rejection)
        List<BlockPos> poly = outline.vertices();
        if (poly == null || poly.size() < 3) return super.randomRelOrigin(rng);

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : poly) {
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }
        // clamp to fallback box around cluster origin to avoid extreme spread
        minX = Math.max(minX, clusterOrigin.getX() - halfX);
        maxX = Math.min(maxX, clusterOrigin.getX() + halfX);
        minZ = Math.max(minZ, clusterOrigin.getZ() - halfZ);
        maxZ = Math.min(maxZ, clusterOrigin.getZ() + halfZ);
        if (minX >= maxX || minZ >= maxZ) return super.randomRelOrigin(rng);

        for (int i = 0; i < maxTries; i++) {
            int x = rng.nextInt(maxX - minX + 1) + minX;
            int z = rng.nextInt(maxZ - minZ + 1) + minZ;
            BlockPos worldP = new BlockPos(x, clusterOrigin.getY(), z);
            if (!BuildConstraintContext.allow(worldP)) continue;
            return new BlockPos(x - clusterOrigin.getX(), 0, z - clusterOrigin.getZ());
        }

        return super.randomRelOrigin(rng);
    }

    @Override
    public boolean canFitRect(BlockPos relOrigin, int width, int depth) {
        if (!super.canFitRect(relOrigin, width, depth)) return false;
        if (outline == null || clusterOrigin == null) return true;

        int w = Math.max(1, width);
        int d = Math.max(1, depth);
        int x0 = clusterOrigin.getX() + relOrigin.getX();
        int z0 = clusterOrigin.getZ() + relOrigin.getZ();
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;

        // Always check corners + center.
        if (!allowXZ(x0, z0)) return false;
        if (!allowXZ(x1, z0)) return false;
        if (!allowXZ(x0, z1)) return false;
        if (!allowXZ(x1, z1)) return false;
        if (!allowXZ((x0 + x1) / 2, (z0 + z1) / 2)) return false;

        // Edge sampling: check a few points along perimeter to ensure the whole footprint stays inside outline.
        int stepX = Math.max(1, Math.min(4, w / 3));
        int stepZ = Math.max(1, Math.min(4, d / 3));

        for (int x = x0; x <= x1; x += stepX) {
            if (!allowXZ(x, z0)) return false;
            if (!allowXZ(x, z1)) return false;
        }
        for (int z = z0; z <= z1; z += stepZ) {
            if (!allowXZ(x0, z)) return false;
            if (!allowXZ(x1, z)) return false;
        }

        return true;
    }

    private boolean allowXZ(int x, int z) {
        return BuildConstraintContext.allow(new BlockPos(x, sampleY, z));
    }
}


