package com.formacraft.server.assembly;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bezier surface math helpers for MetaAssemblyEngine.
 */
public final class AssemblyBezierSurfaceOps {
    private AssemblyBezierSurfaceOps() {}

    public static double[] bezierBasis3(double t) {
        double u = 1.0 - t;
        double b0 = u * u * u;
        double b1 = 3.0 * u * u * t;
        double b2 = 3.0 * u * t * t;
        double b3 = t * t * t;
        return new double[]{b0, b1, b2, b3};
    }

    public static int[][][] sampleBezierSurface(List<int[]> ctrl, int uN, int vN) {
        int[][][] grid = new int[uN + 1][vN + 1][3];
        for (int iu = 0; iu <= uN; iu++) {
            double u = iu / (double) uN;
            double[] Bu = bezierBasis3(u);
            for (int iv = 0; iv <= vN; iv++) {
                double v = iv / (double) vN;
                double[] Bv = bezierBasis3(v);
                double x = 0, y = 0, z = 0;
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        double w = Bu[i] * Bv[j];
                        int[] p = ctrl.get(i * 4 + j);
                        x += p[0] * w;
                        y += p[1] * w;
                        z += p[2] * w;
                    }
                }
                grid[iu][iv][0] = (int) Math.round(x);
                grid[iu][iv][1] = (int) Math.round(y);
                grid[iu][iv][2] = (int) Math.round(z);
            }
        }
        return grid;
    }

    public static List<int[]> readBezierControlPoints(Object ptsObj) {
        // Accept:
        // - flat list of 16 point maps: [{x,y,z}...]
        // - nested 4 rows: [[{x,y,z}..4]..4]
        if (!(ptsObj instanceof List<?> list)) return null;
        List<int[]> out = new ArrayList<>();
        if (!list.isEmpty() && list.getFirst() instanceof List<?>) {
            for (Object rowObj : list) {
                if (!(rowObj instanceof List<?> row)) continue;
                for (Object p : row) {
                    if (p instanceof Map<?, ?> pm) {
                        out.add(new int[]{
                                AssemblyValueParser.i(pm.get("x"), 0),
                                AssemblyValueParser.i(pm.get("y"), 0),
                                AssemblyValueParser.i(pm.get("z"), 0)
                        });
                    }
                }
            }
        } else {
            for (Object p : list) {
                if (p instanceof Map<?, ?> pm) {
                    out.add(new int[]{
                            AssemblyValueParser.i(pm.get("x"), 0),
                            AssemblyValueParser.i(pm.get("y"), 0),
                            AssemblyValueParser.i(pm.get("z"), 0)
                    });
                }
            }
        }
        if (out.size() != 16) return null;
        return out;
    }
}

