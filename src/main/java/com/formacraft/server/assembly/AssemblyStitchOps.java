package com.formacraft.server.assembly;

import net.minecraft.block.BlockState;

import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * Generic seam stitching helpers extracted from MetaAssemblyEngine.
 */
public final class AssemblyStitchOps {
    private AssemblyStitchOps() {}

    @FunctionalInterface
    public interface BeamPlacer {
        void placeBeam(int x0, int y0, int z0, int x1, int y1, int z1, int thickness, int beamH, BlockState state);
    }

    @FunctionalInterface
    public interface PrismPlacer {
        void placePrism(int cx, int cy, int cz, int thickness, int h, BlockState state);
    }

    public static void stitchEdge(
            int nA,
            IntFunction<int[]> pointAAtIndex,
            int nB,
            IntFunction<int[]> pointBAtIndex,
            int thick,
            BlockState mat,
            int capWidth,
            BlockState capMat,
            BeamPlacer beamPlacer,
            PrismPlacer prismPlacer
    ) {
        int n = Math.min(nA, nB);
        if (n < 2) return;
        int[] a0 = pointAAtIndex.apply(0);
        int[] a1 = pointAAtIndex.apply(n - 1);
        int[] b0 = pointBAtIndex.apply(0);
        int[] b1 = pointBAtIndex.apply(n - 1);
        boolean reverse = (AssemblySeamMathOps.dist2(a0, b1) + AssemblySeamMathOps.dist2(a1, b0))
                < (AssemblySeamMathOps.dist2(a0, b0) + AssemblySeamMathOps.dist2(a1, b1));
        for (int i = 0; i < n; i++) {
            int j = reverse ? (n - 1 - i) : i;
            int[] pa = pointAAtIndex.apply(i);
            int[] pb = pointBAtIndex.apply(j);
            beamPlacer.placeBeam(pa[0], pa[1], pa[2], pb[0], pb[1], pb[2], thick, 1, mat);
            if (capWidth > 0 && capMat != null) {
                int mx = (int) Math.round((pa[0] + pb[0]) / 2.0);
                int my = (int) Math.round((pa[1] + pb[1]) / 2.0);
                int mz = (int) Math.round((pa[2] + pb[2]) / 2.0);
                prismPlacer.placePrism(mx, my, mz, capWidth, 1, capMat);
            }
        }
    }

    public static void stitchEdgeResampled(
            int nA,
            IntFunction<int[]> pointAAtIndex,
            java.util.function.DoubleFunction<int[]> pointAAtT,
            int nB,
            IntFunction<int[]> pointBAtIndex,
            java.util.function.DoubleFunction<int[]> pointBAtT,
            boolean reverse,
            int thick,
            BlockState mat,
            int stitchSamples,
            boolean resample,
            int capWidth,
            BlockState capMat,
            BeamPlacer beamPlacer,
            PrismPlacer prismPlacer
    ) {
        int n = (stitchSamples > 0) ? stitchSamples : Math.max(8, Math.min(128, Math.max(nA, nB)));
        if (!resample) n = Math.min(n, Math.min(nA, nB));
        if (n < 2) return;
        for (int i = 0; i < n; i++) {
            double t = (n == 1) ? 0.0 : (i / (double) (n - 1));
            int[] pa = resample ? pointAAtT.apply(t) : pointAAtIndex.apply(i);
            double tt = reverse ? (1.0 - t) : t;
            int[] pb = resample ? pointBAtT.apply(tt) : pointBAtIndex.apply(i);
            beamPlacer.placeBeam(pa[0], pa[1], pa[2], pb[0], pb[1], pb[2], thick, 1, mat);
            if (capWidth > 0 && capMat != null) {
                int mx = (int) Math.round((pa[0] + pb[0]) / 2.0);
                int my = (int) Math.round((pa[1] + pb[1]) / 2.0);
                int mz = (int) Math.round((pa[2] + pb[2]) / 2.0);
                prismPlacer.placePrism(mx, my, mz, capWidth, 1, capMat);
            }
        }
    }

    public static void stitchEdgeResampledRange(
            int nA,
            IntFunction<int[]> pointAAtIndex,
            java.util.function.DoubleFunction<int[]> pointAAtT,
            int nB,
            IntFunction<int[]> pointBAtIndex,
            java.util.function.DoubleFunction<int[]> pointBAtT,
            boolean reverse,
            int thick,
            BlockState mat,
            int stitchSamples,
            boolean resample,
            int capWidth,
            BlockState capMat,
            BeamPlacer beamPlacer,
            PrismPlacer prismPlacer
    ) {
        int n = (stitchSamples > 0) ? stitchSamples : Math.max(8, Math.min(128, Math.max(nA, nB)));
        if (n < 2) return;
        for (int i = 0; i < n; i++) {
            double t = (n == 1) ? 0.0 : (i / (double) (n - 1));
            int[] pa = pointAAtT.apply(t);
            double tt = reverse ? (1.0 - t) : t;
            int[] pb = pointBAtT.apply(tt);
            if (!resample) {
                pa = pointAAtIndex.apply(AssemblyValueParser.clamp((int) Math.round(t * (nA - 1)), 0, nA - 1));
                pb = pointBAtIndex.apply(AssemblyValueParser.clamp((int) Math.round(tt * (nB - 1)), 0, nB - 1));
            }
            beamPlacer.placeBeam(pa[0], pa[1], pa[2], pb[0], pb[1], pb[2], thick, 1, mat);
            if (capWidth > 0 && capMat != null) {
                int mx = (int) Math.round((pa[0] + pb[0]) / 2.0);
                int my = (int) Math.round((pa[1] + pb[1]) / 2.0);
                int mz = (int) Math.round((pa[2] + pb[2]) / 2.0);
                prismPlacer.placePrism(mx, my, mz, capWidth, 1, capMat);
            }
        }
    }
}

