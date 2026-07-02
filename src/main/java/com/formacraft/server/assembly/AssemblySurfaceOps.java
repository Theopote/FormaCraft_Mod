package com.formacraft.server.assembly;

import com.formacraft.server.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;

/**
 * Surface-oriented Assembly ops extracted from {@link MetaAssemblyEngine}.
 */
public final class AssemblySurfaceOps {
    private AssemblySurfaceOps() {}

    public interface Adapter {
        void put(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, int x, int y, int z, BlockState state);
        void placePrism(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, int cx, int cy, int cz, int thickness, int h, BlockState state);
        void placeBeamLine(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, int x0, int y0, int z0, int x1, int y1, int z1, int thickness, int beamH, BlockState state);
        void connectSurfaceGrid(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, int[][][] grid, int uN, int vN, int thick, BlockState mat);
        BlockState pick(MetaAssemblyEngine.Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback);
        int i(Object v, int def);
        double d(Object v, double def);
        boolean bool(Object v, boolean def);
        String str(Object v, String def);
        int clamp(int v, int min, int max);
    }

    public static void applyBezierSurface(List<PlannedBlock> out,
                                          MetaAssemblyEngine.Context ctx,
                                          BlockPos origin,
                                          Map<String, Object> op,
                                          Adapter adapter) {
        Object ptsObj = op.get("points");
        List<int[]> ctrl = AssemblyBezierSurfaceOps.readBezierControlPoints(ptsObj);
        if (ctrl == null || ctrl.size() != 16) return;

        int uN = adapter.clamp(adapter.i(op.get("uSamples"), adapter.i(op.get("u"), 24)), 2, 512);
        int vN = adapter.clamp(adapter.i(op.get("vSamples"), adapter.i(op.get("v"), 24)), 2, 512);
        int thick = adapter.clamp(adapter.i(op.get("thickness"), 1), 1, 9);
        boolean connect = adapter.bool(op.get("connectSamples"), true);

        BlockState mat = adapter.pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0xA57480L, Blocks.QUARTZ_BLOCK.getDefaultState());

        int[][][] grid = new int[uN + 1][vN + 1][3];
        for (int iu = 0; iu <= uN; iu++) {
            double u = iu / (double) uN;
            double[] bu = AssemblyBezierSurfaceOps.bezierBasis3(u);
            for (int iv = 0; iv <= vN; iv++) {
                double v = iv / (double) vN;
                double[] bv = AssemblyBezierSurfaceOps.bezierBasis3(v);
                double x = 0, y = 0, z = 0;
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        double w = bu[i] * bv[j];
                        int[] p = ctrl.get(i * 4 + j);
                        x += p[0] * w;
                        y += p[1] * w;
                        z += p[2] * w;
                    }
                }
                int xi = (int) Math.round(x);
                int yi = (int) Math.round(y);
                int zi = (int) Math.round(z);
                grid[iu][iv][0] = xi;
                grid[iu][iv][1] = yi;
                grid[iu][iv][2] = zi;
                adapter.placePrism(out, ctx, origin, xi, yi, zi, thick, 1, mat);
            }
        }

        if (connect) {
            for (int iu = 0; iu <= uN; iu++) {
                for (int iv = 0; iv <= vN; iv++) {
                    int x = grid[iu][iv][0], y = grid[iu][iv][1], z = grid[iu][iv][2];
                    if (iu + 1 <= uN) {
                        int[] b = grid[iu + 1][iv];
                        adapter.placeBeamLine(out, ctx, origin, x, y, z, b[0], b[1], b[2], thick, 1, mat);
                    }
                    if (iv + 1 <= vN) {
                        int[] b = grid[iu][iv + 1];
                        adapter.placeBeamLine(out, ctx, origin, x, y, z, b[0], b[1], b[2], thick, 1, mat);
                    }
                }
            }
        }
    }

    public static void applySurfaceOffset(List<PlannedBlock> out,
                                          MetaAssemblyEngine.Context ctx,
                                          BlockPos origin,
                                          Map<String, Object> op,
                                          Adapter adapter) {
        Object srcObj = op.get("source");
        if (!(srcObj instanceof Map<?, ?> sm)) return;
        String kind = String.valueOf(sm.get("kind") == null ? "" : sm.get("kind")).trim().toUpperCase(java.util.Locale.ROOT);
        int uN = adapter.clamp(adapter.i(op.get("uSamples"), adapter.i(op.get("u"), adapter.i(sm.get("uSamples"), adapter.i(sm.get("u"), 24)))), 2, 512);
        int vN = adapter.clamp(adapter.i(op.get("vSamples"), adapter.i(op.get("v"), adapter.i(sm.get("vSamples"), adapter.i(sm.get("v"), 24)))), 2, 512);
        int offset = adapter.clamp(adapter.i(op.get("offset"), adapter.i(op.get("distance"), 0)), -32, 32);
        int shellT = adapter.clamp(adapter.i(op.get("shellThickness"), adapter.i(op.get("thickness"), 2)), 1, 16);
        String mode = adapter.str(op.get("mode"), "BOTH").trim().toUpperCase(java.util.Locale.ROOT);
        String normalMode = adapter.str(op.get("normalMode"), adapter.str(op.get("normal_mode"), "DDA")).trim().toUpperCase(java.util.Locale.ROOT);
        double stepLen = Math.max(0.25, Math.min(4.0, adapter.d(op.get("stepLen"), adapter.d(op.get("step_len"), adapter.d(op.get("step"), 1.0)))));
        boolean dedupe = adapter.bool(op.get("dedupe"), adapter.bool(op.get("deDupe"), true));
        boolean connect = adapter.bool(op.get("connectSamples"), adapter.bool(op.get("connect_samples"), false));
        int connectMaxStep = adapter.clamp(adapter.i(op.get("connectMaxStep"), adapter.i(op.get("connect_max_step"), 2)), 1, 16);
        BlockState mat = adapter.pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0x51AFC0L, Blocks.QUARTZ_BLOCK.getDefaultState());

        if (kind.equals("BEZIER_SURFACE")) {
            List<int[]> ctrl = AssemblyBezierSurfaceOps.readBezierControlPoints(sm.get("points"));
            if (ctrl == null || ctrl.size() != 16) return;
            int[][][] grid = AssemblyBezierSurfaceOps.sampleBezierSurface(ctrl, uN, vN);
            surfaceOffsetFromGrid(out, ctx, origin, grid, uN, vN, offset, shellT, mode, normalMode, stepLen, dedupe, connect, connectMaxStep, mat, adapter);
        } else if (kind.equals("BEZIER_SURFACE_SET")) {
            Object patchesObj = sm.get("patches");
            if (!(patchesObj instanceof List<?> pl) || pl.isEmpty()) return;
            for (Object po : pl) {
                if (!(po instanceof Map<?, ?> pm)) continue;
                int ox, oy, oz;
                Object at = pm.get("at");
                if (at instanceof Map<?, ?> am) {
                    ox = adapter.i(am.get("x"), 0);
                    oy = adapter.i(am.get("y"), 0);
                    oz = adapter.i(am.get("z"), 0);
                } else {
                    ox = adapter.i(pm.get("x"), 0);
                    oy = adapter.i(pm.get("y"), 0);
                    oz = adapter.i(pm.get("z"), 0);
                }
                List<int[]> ctrl0 = AssemblyBezierSurfaceOps.readBezierControlPoints(pm.get("points"));
                if (ctrl0 == null || ctrl0.size() != 16) continue;
                java.util.ArrayList<int[]> ctrl = new java.util.ArrayList<>(16);
                for (int[] p : ctrl0) ctrl.add(new int[]{p[0] + ox, p[1] + oy, p[2] + oz});
                int[][][] grid = AssemblyBezierSurfaceOps.sampleBezierSurface(ctrl, uN, vN);
                surfaceOffsetFromGrid(out, ctx, origin, grid, uN, vN, offset, shellT, mode, normalMode, stepLen, dedupe, connect, connectMaxStep, mat, adapter);
            }
        }
    }

    private static void surfaceOffsetFromGrid(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                              int[][][] grid, int uN, int vN, int offset, int shellT, String mode,
                                              String normalMode, double stepLen, boolean dedupe, boolean connect,
                                              int connectMaxStep, BlockState mat, Adapter adapter) {
        if (grid == null) return;
        boolean outSide = mode.isBlank() || mode.equals("BOTH") || mode.equals("OUT") || mode.equals("OUTWARD");
        boolean inSide = mode.equals("BOTH") || mode.equals("IN") || mode.equals("INWARD");
        String nm = (normalMode == null) ? "DDA" : normalMode.trim().toUpperCase(java.util.Locale.ROOT);
        double st = (stepLen <= 0) ? 1.0 : stepLen;

        for (int iu = 0; iu <= uN; iu++) {
            for (int iv = 0; iv <= vN; iv++) {
                int[] p = grid[iu][iv];
                if (p == null) continue;
                int[] pu0 = grid[Math.max(0, iu - 1)][iv];
                int[] pu1 = grid[Math.min(uN, iu + 1)][iv];
                int[] pv0 = grid[iu][Math.max(0, iv - 1)];
                int[] pv1 = grid[iu][Math.min(vN, iv + 1)];
                int dux = (pu1[0] - pu0[0]);
                int duy = (pu1[1] - pu0[1]);
                int duz = (pu1[2] - pu0[2]);
                int dvx = (pv1[0] - pv0[0]);
                int dvy = (pv1[1] - pv0[1]);
                int dvz = (pv1[2] - pv0[2]);

                long nx = (long) duy * dvz - (long) duz * dvy;
                long ny = (long) duz * dvx - (long) dux * dvz;
                long nz = (long) dux * dvy - (long) duy * dvx;
                if (nx == 0 && ny == 0 && nz == 0) continue;

                if (nm.equals("AXIS")) {
                    int ax = (int) Math.signum(nx);
                    int ay = (int) Math.signum(ny);
                    int az = (int) Math.signum(nz);
                    long anx = Math.abs(nx), any = Math.abs(ny), anz = Math.abs(nz);
                    int dx = 0, dy = 0, dz = 0;
                    if (anx >= any && anx >= anz) {
                        dx = ax;
                    } else if (any >= anz) {
                        dy = ay;
                    } else {
                        dz = az;
                    }
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    if (outSide) {
                        int base = offset;
                        for (int t = 0; t < shellT; t++) {
                            int k = base + t;
                            adapter.put(out, ctx, origin, p[0] + dx * k, p[1] + dy * k, p[2] + dz * k, mat);
                        }
                    }
                    if (inSide) {
                        int base = -offset;
                        for (int t = 0; t < shellT; t++) {
                            int k = base + t;
                            adapter.put(out, ctx, origin, p[0] - dx * k, p[1] - dy * k, p[2] - dz * k, mat);
                        }
                    }
                } else {
                    double len = Math.sqrt((double) nx * nx + (double) ny * ny + (double) nz * nz);
                    if (len < 1e-6) continue;
                    double ux = nx / len;
                    double uy = ny / len;
                    double uz = nz / len;

                    if (outSide) {
                        ddaWalkPut(out, ctx, origin, p[0], p[1], p[2], ux, uy, uz, offset, shellT, st, dedupe, connect, connectMaxStep, mat, adapter);
                    }
                    if (inSide) {
                        ddaWalkPut(out, ctx, origin, p[0], p[1], p[2], -ux, -uy, -uz, offset, shellT, st, dedupe, connect, connectMaxStep, mat, adapter);
                    }
                }
            }
        }
    }

    private static void ddaWalkPut(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                   int x0, int y0, int z0, double ux, double uy, double uz, int offset, int shellT,
                                   double stepLen, boolean dedupe, boolean connect, int connectMaxStep, BlockState mat,
                                   Adapter adapter) {
        double fx = x0 + ux * offset;
        double fy = y0 + uy * offset;
        double fz = z0 + uz * offset;

        int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        for (int t = 0; t < shellT; t++) {
            int xi = (int) Math.round(fx);
            int yi = (int) Math.round(fy);
            int zi = (int) Math.round(fz);

            if (!dedupe || xi != lastX || yi != lastY || zi != lastZ) {
                if (connect && lastX != Integer.MIN_VALUE) {
                    int dx = Math.abs(xi - lastX);
                    int dy = Math.abs(yi - lastY);
                    int dz = Math.abs(zi - lastZ);
                    int cheb = Math.max(dx, Math.max(dy, dz));
                    if (cheb > 1 && cheb <= connectMaxStep) {
                        drawVoxelLine(out, ctx, origin, lastX, lastY, lastZ, xi, yi, zi, mat, adapter);
                    } else {
                        adapter.put(out, ctx, origin, xi, yi, zi, mat);
                    }
                } else {
                    adapter.put(out, ctx, origin, xi, yi, zi, mat);
                }
                lastX = xi;
                lastY = yi;
                lastZ = zi;
            }

            fx += ux * stepLen;
            fy += uy * stepLen;
            fz += uz * stepLen;
        }
    }

    private static void drawVoxelLine(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                      int x0, int y0, int z0, int x1, int y1, int z1, BlockState mat, Adapter adapter) {
        int dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (steps <= 0) {
            adapter.put(out, ctx, origin, x0, y0, z0, mat);
            return;
        }
        double sx = dx / (double) steps;
        double sy = dy / (double) steps;
        double sz = dz / (double) steps;
        double fx = x0, fy = y0, fz = z0;
        int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        for (int i = 0; i <= steps; i++) {
            int xi = (int) Math.round(fx);
            int yi = (int) Math.round(fy);
            int zi = (int) Math.round(fz);
            if (xi != lastX || yi != lastY || zi != lastZ) {
                adapter.put(out, ctx, origin, xi, yi, zi, mat);
                lastX = xi;
                lastY = yi;
                lastZ = zi;
            }
            fx += sx;
            fy += sy;
            fz += sz;
        }
    }
}
