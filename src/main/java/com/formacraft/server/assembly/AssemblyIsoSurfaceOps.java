package com.formacraft.server.assembly;

import com.formacraft.server.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Implicit-field and marching isosurface assembly ops extracted from {@link MetaAssemblyEngine}.
 */
public final class AssemblyIsoSurfaceOps {
    private AssemblyIsoSurfaceOps() {}

    public interface Adapter {
        void put(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, int x, int y, int z, BlockState state);
        BlockState pick(MetaAssemblyEngine.Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback);
        int i(Object v, int def);
        double d(Object v, double def);
        String str(Object v, String def);
        int clamp(int v, int min, int max);
    }

    private static final int[][] DIR6 = new int[][]{
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    private static final int[][] TETS = new int[][]{
            {0, 5, 1, 6},
            {0, 1, 2, 6},
            {0, 2, 3, 6},
            {0, 3, 7, 6},
            {0, 7, 4, 6},
            {0, 4, 5, 6}
    };

    public static void applyImplicitField(List<PlannedBlock> out,
                                          MetaAssemblyEngine.Context ctx,
                                          BlockPos origin,
                                          Map<String, Object> op,
                                          Adapter adapter) {
        String kind = adapter.str(op.get("kind"), adapter.str(op.get("field"), "SPHERE")).trim().toUpperCase(Locale.ROOT);
        double iso = adapter.d(op.get("iso"), 0.0);
        double band = adapter.d(op.get("band"), adapter.d(op.get("thickness"), 0.75));
        if (band <= 0) band = 0.75;

        int x0 = adapter.i(op.get("x0"), Integer.MIN_VALUE);
        int x1 = adapter.i(op.get("x1"), Integer.MIN_VALUE);
        int y0 = adapter.i(op.get("y0"), Integer.MIN_VALUE);
        int y1 = adapter.i(op.get("y1"), Integer.MIN_VALUE);
        int z0 = adapter.i(op.get("z0"), Integer.MIN_VALUE);
        int z1 = adapter.i(op.get("z1"), Integer.MIN_VALUE);
        if (x0 == Integer.MIN_VALUE || x1 == Integer.MIN_VALUE || y0 == Integer.MIN_VALUE || y1 == Integer.MIN_VALUE || z0 == Integer.MIN_VALUE || z1 == Integer.MIN_VALUE) {
            int w = adapter.i(op.get("w"), adapter.i(op.get("width"), 32));
            int d0 = adapter.i(op.get("d"), adapter.i(op.get("depth"), 32));
            int h = adapter.i(op.get("h"), adapter.i(op.get("height"), 32));
            int hx = Math.max(1, w / 2);
            int hz = Math.max(1, d0 / 2);
            x0 = -hx;
            x1 = hx;
            y0 = 0;
            y1 = Math.max(1, h);
            z0 = -hz;
            z1 = hz;
        }
        BlockState mat = adapter.pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0x1A2B3C4DL, Blocks.QUARTZ_BLOCK.getDefaultState());

        java.util.ArrayList<double[]> balls = readMetaballs(op.get("metaballs"), adapter);
        double cx = adapter.d(op.get("cx"), adapter.d(op.get("x"), 0.0));
        double cy = adapter.d(op.get("cy"), adapter.d(op.get("y"), 0.0));
        double cz = adapter.d(op.get("cz"), adapter.d(op.get("z"), 0.0));
        if (op.get("center") instanceof Map<?, ?> cm) {
            cx = adapter.d(cm.get("x"), cx);
            cy = adapter.d(cm.get("y"), cy);
            cz = adapter.d(cm.get("z"), cz);
        }
        double r = adapter.d(op.get("r"), adapter.d(op.get("radius"), 10.0));
        double R = adapter.d(op.get("R"), adapter.d(op.get("majorR"), 12.0));
        double rr = adapter.d(op.get("r2"), adapter.d(op.get("minorR"), 4.0));

        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    double fx = x + 0.5;
                    double fy = y + 0.5;
                    double fz = z + 0.5;
                    double f = evalField(kind, fx, fy, fz, cx, cy, cz, r, R, rr, balls) - iso;
                    if (Math.abs(f) > band) continue;
                    boolean surface = false;
                    for (int[] d6 : DIR6) {
                        double f2 = evalField(kind, fx + d6[0], fy + d6[1], fz + d6[2], cx, cy, cz, r, R, rr, balls) - iso;
                        if ((f <= 0 && f2 > 0) || (f > 0 && f2 <= 0)) {
                            surface = true;
                            break;
                        }
                    }
                    if (surface) adapter.put(out, ctx, origin, x, y, z, mat);
                }
            }
        }
    }

    public static void applyMarchingCubes(List<PlannedBlock> out,
                                          MetaAssemblyEngine.Context ctx,
                                          BlockPos origin,
                                          Map<String, Object> op,
                                          Adapter adapter) {
        String kind = adapter.str(op.get("kind"), adapter.str(op.get("field"), "SPHERE")).trim().toUpperCase(Locale.ROOT);
        double iso = adapter.d(op.get("iso"), 0.0);
        int fill = adapter.clamp(adapter.i(op.get("fill"), adapter.i(op.get("samples"), 2)), 1, 8);

        int x0 = adapter.i(op.get("x0"), Integer.MIN_VALUE);
        int x1 = adapter.i(op.get("x1"), Integer.MIN_VALUE);
        int y0 = adapter.i(op.get("y0"), Integer.MIN_VALUE);
        int y1 = adapter.i(op.get("y1"), Integer.MIN_VALUE);
        int z0 = adapter.i(op.get("z0"), Integer.MIN_VALUE);
        int z1 = adapter.i(op.get("z1"), Integer.MIN_VALUE);
        if (x0 == Integer.MIN_VALUE || x1 == Integer.MIN_VALUE || y0 == Integer.MIN_VALUE || y1 == Integer.MIN_VALUE || z0 == Integer.MIN_VALUE || z1 == Integer.MIN_VALUE) {
            int w = adapter.i(op.get("w"), adapter.i(op.get("width"), 32));
            int d0 = adapter.i(op.get("d"), adapter.i(op.get("depth"), 32));
            int h = adapter.i(op.get("h"), adapter.i(op.get("height"), 32));
            int hx = Math.max(1, w / 2);
            int hz = Math.max(1, d0 / 2);
            x0 = -hx;
            x1 = hx;
            y0 = 0;
            y1 = Math.max(1, h);
            z0 = -hz;
            z1 = hz;
        }
        BlockState mat = adapter.pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0x4D0C73A7L, Blocks.QUARTZ_BLOCK.getDefaultState());

        java.util.ArrayList<double[]> balls = readMetaballs(op.get("metaballs"), adapter);
        double cx = adapter.d(op.get("cx"), adapter.d(op.get("x"), 0.0));
        double cy = adapter.d(op.get("cy"), adapter.d(op.get("y"), 0.0));
        double cz = adapter.d(op.get("cz"), adapter.d(op.get("z"), 0.0));
        if (op.get("center") instanceof Map<?, ?> cm) {
            cx = adapter.d(cm.get("x"), cx);
            cy = adapter.d(cm.get("y"), cy);
            cz = adapter.d(cm.get("z"), cz);
        }
        double r = adapter.d(op.get("r"), adapter.d(op.get("radius"), 10.0));
        double R = adapter.d(op.get("R"), adapter.d(op.get("majorR"), 12.0));
        double rr = adapter.d(op.get("r2"), adapter.d(op.get("minorR"), 4.0));

        int ax0 = Math.min(x0, x1), ax1 = Math.max(x0, x1);
        int ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
        int az0 = Math.min(z0, z1), az1 = Math.max(z0, z1);

        for (int x = ax0; x < ax1; x++) {
            for (int y = ay0; y < ay1; y++) {
                for (int z = az0; z < az1; z++) {
                    double[][] p8 = cubeCorners(x, y, z);
                    double[] v8 = new double[8];
                    for (int i = 0; i < 8; i++) {
                        double[] p = p8[i];
                        v8[i] = evalField(kind, p[0], p[1], p[2], cx, cy, cz, r, R, rr, balls) - iso;
                    }
                    marchTetrahedra(out, ctx, origin, p8, v8, mat, fill, adapter);
                }
            }
        }
    }

    private static java.util.ArrayList<double[]> readMetaballs(Object obj, Adapter adapter) {
        java.util.ArrayList<double[]> out = new java.util.ArrayList<>();
        if (!(obj instanceof List<?> list)) return out;
        for (Object it : list) {
            if (!(it instanceof Map<?, ?> m)) continue;
            double x = adapter.d(m.get("x"), 0.0);
            double y = adapter.d(m.get("y"), 0.0);
            double z = adapter.d(m.get("z"), 0.0);
            double r = adapter.d(m.get("r"), adapter.d(m.get("radius"), 6.0));
            out.add(new double[]{x, y, z, r});
        }
        return out;
    }

    private static double evalField(String kind,
                                    double x, double y, double z,
                                    double cx, double cy, double cz,
                                    double r,
                                    double R,
                                    double rr,
                                    java.util.ArrayList<double[]> metaballs) {
        String k = (kind == null) ? "SPHERE" : kind.trim().toUpperCase(Locale.ROOT);
        return switch (k) {
            case "TORUS" -> {
                double dx = x - cx, dy = y - cy, dz = z - cz;
                double qx = Math.sqrt(dx * dx + dz * dz) - R;
                yield Math.sqrt(qx * qx + dy * dy) - rr;
            }
            case "METABALLS", "METABALL" -> {
                if (metaballs == null || metaballs.isEmpty()) {
                    double dx = x - cx, dy = y - cy, dz = z - cz;
                    yield Math.sqrt(dx * dx + dy * dy + dz * dz) - r;
                }
                double s = 0.0;
                for (double[] b : metaballs) {
                    double bx = b[0], by = b[1], bz = b[2], br = Math.max(0.5, b[3]);
                    double dx = x - bx, dy = y - by, dz = z - bz;
                    double d2 = dx * dx + dy * dy + dz * dz + 1e-6;
                    s += (br * br) / d2;
                }
                yield (1.0 - s);
            }
            default -> {
                double dx = x - cx, dy = y - cy, dz = z - cz;
                yield Math.sqrt(dx * dx + dy * dy + dz * dz) - r;
            }
        };
    }

    private static double[][] cubeCorners(int x, int y, int z) {
        return new double[][]{
                {x, y, z},
                {x + 1, y, z},
                {x + 1, y, z + 1},
                {x, y, z + 1},
                {x, y + 1, z},
                {x + 1, y + 1, z},
                {x + 1, y + 1, z + 1},
                {x, y + 1, z + 1}
        };
    }

    private static void marchTetrahedra(List<PlannedBlock> out,
                                        MetaAssemblyEngine.Context ctx,
                                        BlockPos origin,
                                        double[][] p8,
                                        double[] v8,
                                        BlockState mat,
                                        int fill,
                                        Adapter adapter) {
        for (int[] tet : TETS) {
            int a = tet[0], b = tet[1], c = tet[2], d = tet[3];
            double va = v8[a], vb = v8[b], vc = v8[c], vd = v8[d];
            boolean ia = va <= 0, ib = vb <= 0, ic = vc <= 0, id = vd <= 0;
            int inside = (ia ? 1 : 0) + (ib ? 1 : 0) + (ic ? 1 : 0) + (id ? 1 : 0);
            if (inside == 0 || inside == 4) continue;

            int[] ids = new int[]{a, b, c, d};
            boolean[] ins = new boolean[]{ia, ib, ic, id};
            int[] inIdx = new int[4];
            int[] outIdx = new int[4];
            int ni = 0, no = 0;
            for (int i = 0; i < 4; i++) {
                if (ins[i]) inIdx[ni++] = i;
                else outIdx[no++] = i;
            }

            if (inside == 1 || inside == 3) {
                int vi = (inside == 1) ? inIdx[0] : outIdx[0];
                int vj = (inside == 1) ? outIdx[0] : inIdx[0];
                int vk = (inside == 1) ? outIdx[1] : inIdx[1];
                int vl = (inside == 1) ? outIdx[2] : inIdx[2];

                double[] p0 = interpIso(p8[ids[vi]], p8[ids[vj]], v8[ids[vi]], v8[ids[vj]]);
                double[] p1 = interpIso(p8[ids[vi]], p8[ids[vk]], v8[ids[vi]], v8[ids[vk]]);
                double[] p2 = interpIso(p8[ids[vi]], p8[ids[vl]], v8[ids[vi]], v8[ids[vl]]);
                voxelizeTriangle(out, ctx, origin, p0, p1, p2, mat, fill, adapter);
            } else if (inside == 2) {
                int v0 = inIdx[0], v1 = inIdx[1];
                int v2 = outIdx[0], v3 = outIdx[1];
                double[] p02 = interpIso(p8[ids[v0]], p8[ids[v2]], v8[ids[v0]], v8[ids[v2]]);
                double[] p03 = interpIso(p8[ids[v0]], p8[ids[v3]], v8[ids[v0]], v8[ids[v3]]);
                double[] p12 = interpIso(p8[ids[v1]], p8[ids[v2]], v8[ids[v1]], v8[ids[v2]]);
                double[] p13 = interpIso(p8[ids[v1]], p8[ids[v3]], v8[ids[v1]], v8[ids[v3]]);
                voxelizeTriangle(out, ctx, origin, p02, p12, p03, mat, fill, adapter);
                voxelizeTriangle(out, ctx, origin, p12, p13, p03, mat, fill, adapter);
            }
        }
    }

    private static double[] interpIso(double[] p0, double[] p1, double v0, double v1) {
        double dv = v1 - v0;
        double t = (Math.abs(dv) < 1e-9) ? 0.5 : (0.0 - v0) / dv;
        t = Math.max(0.0, Math.min(1.0, t));
        return new double[]{
                AssemblyBezierOps.lerp(p0[0], p1[0], t),
                AssemblyBezierOps.lerp(p0[1], p1[1], t),
                AssemblyBezierOps.lerp(p0[2], p1[2], t)
        };
    }

    private static void voxelizeTriangle(List<PlannedBlock> out,
                                         MetaAssemblyEngine.Context ctx,
                                         BlockPos origin,
                                         double[] a,
                                         double[] b,
                                         double[] c,
                                         BlockState mat,
                                         int fill,
                                         Adapter adapter) {
        if (a == null || b == null || c == null) return;
        double ab = dist(a, b);
        double bc = dist(b, c);
        double ca = dist(c, a);
        int n = Math.max(2, (int) Math.ceil(Math.max(ab, Math.max(bc, ca)) * Math.max(1, fill)));
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= n - i; j++) {
                double u = i / (double) n;
                double v = j / (double) n;
                double w = 1.0 - u - v;
                double x = a[0] * w + b[0] * u + c[0] * v;
                double y = a[1] * w + b[1] * u + c[1] * v;
                double z = a[2] * w + b[2] * u + c[2] * v;
                adapter.put(out, ctx, origin, (int) Math.round(x), (int) Math.round(y), (int) Math.round(z), mat);
            }
        }
    }

    private static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
