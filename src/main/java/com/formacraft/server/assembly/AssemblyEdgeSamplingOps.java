package com.formacraft.server.assembly;

import java.util.function.DoubleFunction;
import java.util.function.IntFunction;

/**
 * Generic edge sampling / error scoring helpers for seam stitching.
 */
public final class AssemblyEdgeSamplingOps {
    private AssemblyEdgeSamplingOps() {}

    public static int[] edgePointAt(int count, IntFunction<int[]> pointAtIndex, double t) {
        if (count <= 1) return pointAtIndex.apply(0);
        double idx = t * (count - 1);
        int i0 = AssemblyValueParser.clamp((int) Math.floor(idx), 0, count - 1);
        int i1 = AssemblyValueParser.clamp(i0 + 1, 0, count - 1);
        double a = idx - i0;
        int[] p0 = pointAtIndex.apply(i0);
        int[] p1 = pointAtIndex.apply(i1);
        int x = (int) Math.round(AssemblyBezierOps.lerp(p0[0], p1[0], a));
        int y = (int) Math.round(AssemblyBezierOps.lerp(p0[1], p1[1], a));
        int z = (int) Math.round(AssemblyBezierOps.lerp(p0[2], p1[2], a));
        return new int[]{x, y, z};
    }

    public static int[] edgePointAtRange(
            int count,
            IntFunction<int[]> pointAtIndex,
            double t,
            double t0,
            double t1
    ) {
        double a = Math.min(t0, t1);
        double b = Math.max(t0, t1);
        a = Math.max(0.0, Math.min(1.0, a));
        b = Math.max(0.0, Math.min(1.0, b));
        double tt = a + (b - a) * t;
        return edgePointAt(count, pointAtIndex, tt);
    }

    public static double edgeMse(
            int countA,
            IntFunction<int[]> pointAAtIndex,
            DoubleFunction<int[]> pointAAtT,
            int countB,
            IntFunction<int[]> pointBAtIndex,
            DoubleFunction<int[]> pointBAtT,
            boolean reverse,
            int n,
            boolean resample
    ) {
        if (n < 2) n = 2;
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) (n - 1);
            int[] pa = resample
                    ? pointAAtT.apply(t)
                    : pointAAtIndex.apply(AssemblyValueParser.clamp((int) Math.round(t * (countA - 1)), 0, countA - 1));
            double tt = reverse ? (1.0 - t) : t;
            int[] pb = resample
                    ? pointBAtT.apply(tt)
                    : pointBAtIndex.apply(AssemblyValueParser.clamp((int) Math.round(tt * (countB - 1)), 0, countB - 1));
            sum += AssemblySeamMathOps.dist2(pa, pb);
        }
        return sum / n;
    }

    public static double edgeMseRange(
            IntFunction<int[]> pointAAtIndex,
            DoubleFunction<int[]> pointAAtT,
            int countA,
            IntFunction<int[]> pointBAtIndex,
            DoubleFunction<int[]> pointBAtT,
            int countB,
            boolean reverse,
            int n,
            boolean resample
    ) {
        if (n < 2) n = 2;
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            double t = (n == 1) ? 0.0 : (i / (double) (n - 1));
            int[] pa = pointAAtT.apply(t);
            double tt = reverse ? (1.0 - t) : t;
            int[] pb = pointBAtT.apply(tt);
            if (!resample) {
                pa = pointAAtIndex.apply(AssemblyValueParser.clamp((int) Math.round(t * (countA - 1)), 0, countA - 1));
                pb = pointBAtIndex.apply(AssemblyValueParser.clamp((int) Math.round(tt * (countB - 1)), 0, countB - 1));
            }
            sum += AssemblySeamMathOps.dist2(pa, pb);
        }
        return sum / n;
    }
}

