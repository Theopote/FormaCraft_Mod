package com.formacraft.server.assembly;

import java.util.function.IntFunction;

/**
 * Seam/math primitives extracted from MetaAssemblyEngine.
 */
public final class AssemblySeamMathOps {
    private AssemblySeamMathOps() {}

    public static long packXYZ(int x, int y, int z) {
        // pack 3 signed ints into one long (range-limited; good enough for local coords)
        long xx = (x & 0x1FFFFF); // 21 bits
        long yy = (y & 0x3FFFF);  // 18 bits
        long zz = (z & 0x1FFFFF); // 21 bits
        return (xx << 42) | (yy << 24) | zz;
    }

    public static long packUV(int u, int v) {
        return (((long) u) << 32) ^ (v & 0xffffffffL);
    }

    public static long dist2(int[] a, int[] b) {
        long dx = (long) a[0] - b[0];
        long dy = (long) a[1] - b[1];
        long dz = (long) a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    public static String edgeSignature(int n, boolean reverse, IntFunction<int[]> edgePointAtIndex) {
        StringBuilder sb = new StringBuilder(n * 12);
        if (!reverse) {
            for (int i = 0; i < n; i++) {
                int[] p = edgePointAtIndex.apply(i);
                sb.append(packXYZ(p[0], p[1], p[2])).append(';');
            }
        } else {
            for (int i = n - 1; i >= 0; i--) {
                int[] p = edgePointAtIndex.apply(i);
                sb.append(packXYZ(p[0], p[1], p[2])).append(';');
            }
        }
        return sb.toString();
    }
}

