package com.formacraft.common.generation.component.util;

import com.formacraft.common.patch.BlockPatch;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 将归一化 profile 绕 Y 轴旋转，输出实心冠部 BlockPatch（REVOLVE_SURFACE 的 component 路径版）。
 */
public final class ComponentCrownRevolveSolver {

    private static final int MAX_PATCHES = 4000;

    private ComponentCrownRevolveSolver() {}

    public static void emitRevolveSolid(
            List<BlockPatch> out,
            int centerX,
            int baseY,
            int centerZ,
            double radiusScale,
            int heightBlocks,
            List<double[]> normalizedProfile,
            String blockId,
            int segments
    ) {
        if (out == null || normalizedProfile == null || normalizedProfile.size() < 2) {
            return;
        }
        if (blockId == null || blockId.isBlank()) {
            blockId = "minecraft:quartz_block";
        }
        int ySteps = Math.max(1, heightBlocks);
        double[] maxRadius = new double[ySteps + 1];
        for (int yi = 0; yi <= ySteps; yi++) {
            double yNorm = yi / (double) ySteps;
            maxRadius[yi] = interpolateRadius(normalizedProfile, yNorm) * radiusScale;
        }

        Set<Long> seen = new HashSet<>();
        for (int yi = 0; yi <= ySteps; yi++) {
            if (out.size() >= MAX_PATCHES) {
                return;
            }
            double rMax = maxRadius[yi];
            if (rMax <= 0.05) {
                continue;
            }
            int y = baseY + yi;
            int bound = (int) Math.ceil(rMax);
            for (int dx = -bound; dx <= bound; dx++) {
                for (int dz = -bound; dz <= bound; dz++) {
                    if (dx * dx + dz * dz > rMax * rMax + 0.25) {
                        continue;
                    }
                    long key = pack(centerX + dx, y, centerZ + dz);
                    if (!seen.add(key)) {
                        continue;
                    }
                    out.add(new BlockPatch(BlockPatch.PLACE, centerX + dx, y, centerZ + dz, blockId));
                    if (out.size() >= MAX_PATCHES) {
                        return;
                    }
                }
            }
        }
    }

    static double interpolateRadius(List<double[]> profile, double yNorm) {
        if (profile.isEmpty()) {
            return 0.0;
        }
        if (yNorm <= profile.getFirst()[1]) {
            return profile.getFirst()[0];
        }
        if (yNorm >= profile.getLast()[1]) {
            return profile.getLast()[0];
        }
        for (int i = 0; i < profile.size() - 1; i++) {
            double[] a = profile.get(i);
            double[] b = profile.get(i + 1);
            if (yNorm < a[1] || yNorm > b[1]) {
                continue;
            }
            double span = b[1] - a[1];
            if (span <= 1e-6) {
                return b[0];
            }
            double t = (yNorm - a[1]) / span;
            return a[0] + (b[0] - a[0]) * t;
        }
        return profile.getLast()[0];
    }

    private static long pack(int x, int y, int z) {
        return (((long) x) << 42) ^ (((long) y) << 21) ^ (z & 0x1fffffL);
    }
}
