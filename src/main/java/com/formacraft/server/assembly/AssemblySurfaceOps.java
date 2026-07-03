package com.formacraft.server.assembly;

import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Map;

/**
 * Surface-oriented Assembly ops extracted from {@link MetaAssemblyEngine}.
 */
public final class AssemblySurfaceOps {
    private AssemblySurfaceOps() {}

    private static final FcaLog LOG = FcaLog.of("AssemblySurfaceOps");

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

    public static void applyRevolveSurface(List<PlannedBlock> out,
                                           MetaAssemblyEngine.Context ctx,
                                           BlockPos origin,
                                           Map<String, Object> op,
                                           Adapter adapter) {
        Object profObj = op.get("profileRings");
        if (profObj == null) profObj = op.get("rings");
        if (profObj == null) profObj = op.get("profilePoints");
        if (profObj == null) profObj = op.get("points");
        List<int[]> profile = read2DProfilePoints(profObj, adapter);
        if (profile == null || profile.size() < 2) return;

        int seg = adapter.clamp(adapter.i(op.get("segments"), 48), 8, 512);
        double angleDeg = adapter.d(op.get("angleDeg"), adapter.d(op.get("angle"), 360.0));
        if (Double.isNaN(angleDeg) || angleDeg <= 0.0) angleDeg = 360.0;
        if (angleDeg > 360.0) angleDeg = 360.0;
        int thick = adapter.clamp(adapter.i(op.get("thickness"), 1), 1, 9);
        boolean connect = adapter.bool(op.get("connectSamples"), true);
        BlockState mat = adapter.pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0x93F3B1L, Blocks.QUARTZ_BLOCK.getDefaultState());

        int nTheta = (int) Math.round(seg * (angleDeg / 360.0));
        nTheta = adapter.clamp(nTheta, 3, 512);
        int nP = profile.size();
        int[][][] grid = new int[nTheta + 1][nP][3];

        for (int it = 0; it <= nTheta; it++) {
            double t = it / (double) nTheta;
            double theta = Math.toRadians(angleDeg * t);
            double cs = Math.cos(theta);
            double sn = Math.sin(theta);
            for (int ip = 0; ip < nP; ip++) {
                int[] pr = profile.get(ip);
                double r = pr[0];
                double y = pr[1];
                int x = (int) Math.round(r * cs);
                int z = (int) Math.round(r * sn);
                int yy = (int) Math.round(y);
                grid[it][ip][0] = x;
                grid[it][ip][1] = yy;
                grid[it][ip][2] = z;
                adapter.placePrism(out, ctx, origin, x, yy, z, thick, 1, mat);
            }
        }
        if (connect) {
            for (int it = 0; it <= nTheta; it++) {
                for (int ip = 0; ip < nP; ip++) {
                    int x = grid[it][ip][0], y = grid[it][ip][1], z = grid[it][ip][2];
                    if (it + 1 <= nTheta) {
                        int[] b = grid[it + 1][ip];
                        adapter.placeBeamLine(out, ctx, origin, x, y, z, b[0], b[1], b[2], thick, 1, mat);
                    }
                    if (ip + 1 < nP) {
                        int[] b = grid[it][ip + 1];
                        adapter.placeBeamLine(out, ctx, origin, x, y, z, b[0], b[1], b[2], thick, 1, mat);
                    }
                }
            }
        }
    }

    public static void applyLoftSurface(List<PlannedBlock> out,
                                        MetaAssemblyEngine.Context ctx,
                                        BlockPos origin,
                                        Map<String, Object> op,
                                        Adapter adapter) {
        Object secObj = op.get("sections");
        if (!(secObj instanceof List<?> secs) || secs.size() < 2) return;

        List<LoftSection> sections = new java.util.ArrayList<>();
        for (Object sObj : secs) {
            if (!(sObj instanceof Map<?, ?> sm)) continue;
            Object atObj = sm.get("at");
            int ax, ay, az;
            if (atObj instanceof Map<?, ?> am) {
                ax = adapter.i(am.get("x"), 0);
                ay = adapter.i(am.get("y"), 0);
                az = adapter.i(am.get("z"), 0);
            } else {
                ax = adapter.i(sm.get("x"), 0);
                ay = adapter.i(sm.get("y"), 0);
                az = adapter.i(sm.get("z"), 0);
            }
            Object profObj = sm.get("profileRings");
            if (profObj == null) profObj = sm.get("rings");
            if (profObj == null) profObj = sm.get("profilePoints");
            List<int[]> prof = read2DProfilePoints(profObj, adapter);
            if (prof == null || prof.size() < 2) continue;
            sections.add(new LoftSection(ax, ay, az, prof));
        }
        if (sections.size() < 2) return;

        int nP = sections.getFirst().profile.size();
        boolean ok = true;
        for (LoftSection s : sections) if (s.profile.size() != nP) { ok = false; break; }
        if (!ok) return;

        int uN = adapter.clamp(adapter.i(op.get("uSamples"), adapter.i(op.get("u"), 24)), 2, 512);
        int thick = adapter.clamp(adapter.i(op.get("thickness"), 1), 1, 9);
        boolean connect = adapter.bool(op.get("connectSamples"), true);
        BlockState mat = adapter.pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0x0E11F3L, Blocks.QUARTZ_BLOCK.getDefaultState());

        for (int si = 0; si < sections.size() - 1; si++) {
            LoftSection a = sections.get(si);
            LoftSection b = sections.get(si + 1);
            int[][][] grid = new int[uN + 1][nP][3];
            for (int iu = 0; iu <= uN; iu++) {
                double t = iu / (double) uN;
                double ox = AssemblyBezierOps.lerp(a.x, b.x, t);
                double oy = AssemblyBezierOps.lerp(a.y, b.y, t);
                double oz = AssemblyBezierOps.lerp(a.z, b.z, t);
                for (int ip = 0; ip < nP; ip++) {
                    int[] pa = a.profile.get(ip);
                    int[] pb = b.profile.get(ip);
                    double px = AssemblyBezierOps.lerp(pa[0], pb[0], t);
                    double py = AssemblyBezierOps.lerp(pa[1], pb[1], t);
                    int x = (int) Math.round(ox + px);
                    int y = (int) Math.round(oy + py);
                    int z = (int) Math.round(oz);
                    grid[iu][ip][0] = x;
                    grid[iu][ip][1] = y;
                    grid[iu][ip][2] = z;
                    adapter.placePrism(out, ctx, origin, x, y, z, thick, 1, mat);
                }
            }
            if (connect) {
                for (int iu = 0; iu <= uN; iu++) {
                    for (int ip = 0; ip < nP; ip++) {
                        int x = grid[iu][ip][0], y = grid[iu][ip][1], z = grid[iu][ip][2];
                        if (iu + 1 <= uN) {
                            int[] bb = grid[iu + 1][ip];
                            adapter.placeBeamLine(out, ctx, origin, x, y, z, bb[0], bb[1], bb[2], thick, 1, mat);
                        }
                        if (ip + 1 < nP) {
                            int[] bb = grid[iu][ip + 1];
                            adapter.placeBeamLine(out, ctx, origin, x, y, z, bb[0], bb[1], bb[2], thick, 1, mat);
                        }
                    }
                }
            }
        }
    }

    public static void applySplineSweep(List<PlannedBlock> out,
                                        MetaAssemblyEngine.Context ctx,
                                        BlockPos origin,
                                        Map<String, Object> op,
                                        Adapter adapter) {
        List<Vec3d> pts = AssemblyBezierOps.parseVecPoints(op.get("points"));
        if (pts.size() < 2) return;

        int samplesPerBlock = adapter.clamp(adapter.i(op.get("samplesPerBlock"), 10), 2, 40);
        List<Vec3d> poly = AssemblyBezierOps.sampleBezierSpline(pts, samplesPerBlock);
        if (poly.size() < 2) return;

        String profile = adapter.str(op.get("profile"), "SPHERE").trim().toUpperCase(java.util.Locale.ROOT);
        String profileFrame = adapter.str(op.get("profileFrame"), adapter.str(op.get("frame"), "PATH")).trim().toUpperCase(java.util.Locale.ROOT);
        String snapMode = adapter.str(op.get("profileSnap"), adapter.str(op.get("snap"), "ROUND")).trim().toUpperCase(java.util.Locale.ROOT);
        int r = adapter.clamp(adapter.i(op.get("r"), adapter.i(op.get("radius"), 3)), 1, 24);
        int r0 = adapter.i(op.get("r0"), adapter.i(op.get("radius0"), Integer.MIN_VALUE));
        int r1 = adapter.i(op.get("r1"), adapter.i(op.get("radius1"), Integer.MIN_VALUE));
        boolean taper = (r0 != Integer.MIN_VALUE && r1 != Integer.MIN_VALUE);
        if (!taper) {
            r0 = r;
            r1 = r;
        }

        boolean hollow = adapter.bool(op.get("hollow"), false);
        int thickness = adapter.clamp(adapter.i(op.get("thickness"), 1), 1, 8);
        double twistTurns = adapter.d(op.get("twistTurns"), 0.0);
        double twistPhase = adapter.d(op.get("twistPhase"), 0.0);

        BlockState mat = adapter.pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0xA58001L, Blocks.WHITE_CONCRETE.getDefaultState());
        BlockState shell = adapter.pick(ctx, op, "wall", "WALL_BASE", 0xA58002L, mat);

        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        boolean connectSamples = adapter.bool(op.get("connectSamples"), false);
        int connectMaxStep = adapter.clamp(adapter.i(op.get("connectMaxStep"), 2), 1, 8);
        java.util.HashMap<Long, long[]> lastSection = connectSamples ? new java.util.HashMap<>() : null;
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            double tt = (n <= 1) ? 0.0 : (i / (double) (n - 1));
            double rad = AssemblyBezierOps.lerp(r0, r1, tt);
            Vec3d p = poly.get(i);
            int cx = (int) Math.round(p.x);
            int cy = (int) Math.round(p.y);
            int cz = (int) Math.round(p.z);

            if (!profile.contains("RECT") && !profile.contains("POLY")) {
                int rr = Math.max(1, (int) Math.round(rad));
                int rr2 = rr * rr;
                int inner = Math.max(0, rr - thickness);
                int inner2 = inner * inner;
                for (int ox = -rr; ox <= rr; ox++) {
                    for (int oy = -rr; oy <= rr; oy++) {
                        for (int oz = -rr; oz <= rr; oz++) {
                            int d2 = ox * ox + oy * oy + oz * oz;
                            if (d2 > rr2) continue;
                            if (hollow && d2 < inner2) continue;
                            int x = cx + ox;
                            int y = cy + oy;
                            int z = cz + oz;
                            long key = AssemblySeamMathOps.packXYZ(x, y, z);
                            if (!seen.add(key)) continue;
                            adapter.put(out, ctx, origin, x, y, z, hollow ? shell : mat);
                        }
                    }
                }
                continue;
            }

            int pwConst = adapter.clamp(adapter.i(op.get("profileW"), adapter.i(op.get("w"), 5)), 1, 64);
            int phConst = adapter.clamp(adapter.i(op.get("profileH"), adapter.i(op.get("h"), 3)), 1, 64);
            int pw0 = adapter.i(op.get("profileW0"), adapter.i(op.get("w0"), Integer.MIN_VALUE));
            int pw1 = adapter.i(op.get("profileW1"), adapter.i(op.get("w1"), Integer.MIN_VALUE));
            int ph0 = adapter.i(op.get("profileH0"), adapter.i(op.get("h0"), Integer.MIN_VALUE));
            int ph1 = adapter.i(op.get("profileH1"), adapter.i(op.get("h1"), Integer.MIN_VALUE));
            boolean rectTaper = (pw0 != Integer.MIN_VALUE && pw1 != Integer.MIN_VALUE) || (ph0 != Integer.MIN_VALUE && ph1 != Integer.MIN_VALUE);
            if (!rectTaper) {
                pw0 = pwConst;
                pw1 = pwConst;
                ph0 = phConst;
                ph1 = phConst;
            } else {
                if (pw0 == Integer.MIN_VALUE) pw0 = pwConst;
                if (pw1 == Integer.MIN_VALUE) pw1 = pwConst;
                if (ph0 == Integer.MIN_VALUE) ph0 = phConst;
                if (ph1 == Integer.MIN_VALUE) ph1 = phConst;
            }
            int pw = adapter.clamp((int) Math.round(AssemblyBezierOps.lerp(pw0, pw1, tt)), 1, 64);
            int ph = adapter.clamp((int) Math.round(AssemblyBezierOps.lerp(ph0, ph1, tt)), 1, 64);
            int halfW = Math.max(0, pw / 2);
            int halfH = Math.max(0, ph / 2);
            int t = Math.max(1, thickness);
            boolean capEnds = adapter.bool(op.get("capEnds"), hollow);
            boolean carveInterior = adapter.bool(op.get("carveInterior"), false);
            int capThickness = adapter.clamp(adapter.i(op.get("capThickness"), t), 1, 8);

            Vec3d prev = (i > 0) ? poly.get(i - 1) : poly.get(i);
            Vec3d next = (i + 1 < n) ? poly.get(i + 1) : poly.get(i);
            Vec3d tan = next.subtract(prev);
            if (tan.lengthSquared() < 1e-6) tan = new Vec3d(0, 0, 1);
            tan = tan.normalize();

            Vec3d nrm;
            Vec3d bin;
            switch (profileFrame) {
                case "WORLD_XY" -> {
                    nrm = new Vec3d(1, 0, 0);
                    bin = new Vec3d(0, 1, 0);
                }
                case "WORLD_XZ" -> {
                    nrm = new Vec3d(1, 0, 0);
                    bin = new Vec3d(0, 0, 1);
                }
                case "WORLD_YZ" -> {
                    nrm = new Vec3d(0, 1, 0);
                    bin = new Vec3d(0, 0, 1);
                }
                case null, default -> {
                    Vec3d up = new Vec3d(0, 1, 0);
                    if (Math.abs(tan.dotProduct(up)) > 0.95) up = new Vec3d(1, 0, 0);
                    nrm = up.crossProduct(tan).normalize();
                    bin = tan.crossProduct(nrm).normalize();
                }
            }

            double ang = (twistTurns * Math.PI * 2.0) * tt + (twistPhase * Math.PI * 2.0);
            double ca = Math.cos(ang);
            double sa = Math.sin(ang);
            Vec3d nrm2 = nrm.multiply(ca).add(bin.multiply(sa));
            Vec3d bin2 = bin.multiply(ca).subtract(nrm.multiply(sa));

            if (profile.contains("POLY")) {
                List<List<int[]>> rings2 = AssemblyProfilePolygonOps.parseProfileRings(op);
                if (rings2.isEmpty() || rings2.getFirst().size() < 3) return;
                double s0 = adapter.d(op.get("profileScale0"), adapter.d(op.get("scale0"), 1.0));
                double s1 = adapter.d(op.get("profileScale1"), adapter.d(op.get("scale1"), 1.0));
                double sc = AssemblyBezierOps.lerp(s0, s1, tt);
                if (sc <= 0.05) sc = 0.05;
                int[] bb = AssemblyProfilePolygonOps.boundsRings2D(rings2);
                int uMin = (int) Math.floor(bb[0] * sc);
                int uMax = (int) Math.ceil(bb[1] * sc);
                int vMin = (int) Math.floor(bb[2] * sc);
                int vMax = (int) Math.ceil(bb[3] * sc);
                int area2d = (uMax - uMin + 1) * (vMax - vMin + 1);
                if (area2d > 20000) return;

                List<List<int[]>> sr = AssemblyProfilePolygonOps.scaleRings(rings2, sc);

                for (int uu = uMin; uu <= uMax; uu++) {
                    for (int vv = vMin; vv <= vMax; vv++) {
                        boolean inside = AssemblyProfilePolygonOps.pointInRings2D(uu, vv, sr);
                        if (!inside) continue;
                        boolean border = true;
                        if (hollow) {
                            border = AssemblyProfilePolygonOps.isRingsBorder(uu, vv, sr, t);
                            if (!border && !carveInterior) continue;
                        }
                        Vec3d off = nrm2.multiply(uu).add(bin2.multiply(vv));
                        int x = cx + snap(off.x, snapMode);
                        int y = cy + snap(off.y, snapMode);
                        int z = cz + snap(off.z, snapMode);
                        if (connectSamples && lastSection != null) {
                            BlockState s = (!hollow) ? mat : (border ? shell : Blocks.AIR.getDefaultState());
                            connectToLast(out, ctx, origin, adapter, lastSection, AssemblySeamMathOps.packUV(uu, vv), x, y, z, s, seen, connectMaxStep);
                        }
                        long key = AssemblySeamMathOps.packXYZ(x, y, z);
                        if (!seen.add(key)) continue;
                        if (!hollow) adapter.put(out, ctx, origin, x, y, z, mat);
                        else adapter.put(out, ctx, origin, x, y, z, border ? shell : Blocks.AIR.getDefaultState());
                    }
                }

                if (hollow && capEnds && (i == 0 || i == n - 1)) {
                    for (int uu = uMin; uu <= uMax; uu++) {
                        for (int vv = vMin; vv <= vMax; vv++) {
                            boolean inside = AssemblyProfilePolygonOps.pointInRings2D(uu, vv, sr);
                            if (!inside) continue;
                            boolean border = AssemblyProfilePolygonOps.isRingsBorder(uu, vv, sr, capThickness);
                            if (!border) continue;
                            Vec3d off = nrm2.multiply(uu).add(bin2.multiply(vv));
                            int x = cx + snap(off.x, snapMode);
                            int y = cy + snap(off.y, snapMode);
                            int z = cz + snap(off.z, snapMode);
                            if (connectSamples && lastSection != null) {
                                connectToLast(out, ctx, origin, adapter, lastSection, AssemblySeamMathOps.packUV(uu, vv), x, y, z, shell, seen, connectMaxStep);
                            }
                            long key = AssemblySeamMathOps.packXYZ(x, y, z);
                            if (!seen.add(key)) continue;
                            adapter.put(out, ctx, origin, x, y, z, shell);
                        }
                    }
                }
                continue;
            }

            for (int uu = -halfW; uu <= halfW; uu++) {
                for (int vv = -halfH; vv <= halfH; vv++) {
                    boolean border = true;
                    if (hollow) {
                        border = (uu - (-halfW) < t) || (halfW - uu < t) || (vv - (-halfH) < t) || (halfH - vv < t);
                        if (!border && !carveInterior) continue;
                    }
                    Vec3d off = nrm2.multiply(uu).add(bin2.multiply(vv));
                    int x = cx + snap(off.x, snapMode);
                    int y = cy + snap(off.y, snapMode);
                    int z = cz + snap(off.z, snapMode);
                    if (connectSamples && lastSection != null) {
                        BlockState s = (!hollow) ? mat : (border ? shell : Blocks.AIR.getDefaultState());
                        connectToLast(out, ctx, origin, adapter, lastSection, AssemblySeamMathOps.packUV(uu, vv), x, y, z, s, seen, connectMaxStep);
                    }
                    long key = AssemblySeamMathOps.packXYZ(x, y, z);
                    if (!seen.add(key)) continue;
                    if (!hollow) {
                        adapter.put(out, ctx, origin, x, y, z, mat);
                    } else {
                        adapter.put(out, ctx, origin, x, y, z, border ? shell : Blocks.AIR.getDefaultState());
                    }
                }
            }

            if (hollow && capEnds && (i == 0 || i == n - 1)) {
                for (int uu = -halfW; uu <= halfW; uu++) {
                    for (int vv = -halfH; vv <= halfH; vv++) {
                        boolean border = (uu - (-halfW) < capThickness) || (halfW - uu < capThickness) || (vv - (-halfH) < capThickness) || (halfH - vv < capThickness);
                        if (!border) continue;
                        Vec3d off = nrm2.multiply(uu).add(bin2.multiply(vv));
                        int x = cx + snap(off.x, snapMode);
                        int y = cy + snap(off.y, snapMode);
                        int z = cz + snap(off.z, snapMode);
                        if (connectSamples && lastSection != null) {
                            connectToLast(out, ctx, origin, adapter, lastSection, AssemblySeamMathOps.packUV(uu, vv), x, y, z, shell, seen, connectMaxStep);
                        }
                        long key = AssemblySeamMathOps.packXYZ(x, y, z);
                        if (!seen.add(key)) continue;
                        adapter.put(out, ctx, origin, x, y, z, shell);
                    }
                }
            }
        }
    }

    public static void applyBezierSurfaceSet(List<PlannedBlock> out,
                                             MetaAssemblyEngine.Context ctx,
                                             BlockPos origin,
                                             Map<String, Object> op,
                                             Adapter adapter) {
        Object patchesObj = op.get("patches");
        if (!(patchesObj instanceof List<?> patchesList) || patchesList.isEmpty()) return;

        int uDef = adapter.clamp(adapter.i(op.get("uSamples"), adapter.i(op.get("u"), 24)), 2, 512);
        int vDef = adapter.clamp(adapter.i(op.get("vSamples"), adapter.i(op.get("v"), 24)), 2, 512);
        int thickDef = adapter.clamp(adapter.i(op.get("thickness"), 1), 1, 9);
        boolean connectDef = adapter.bool(op.get("connectSamples"), true);
        boolean stitch = adapter.bool(op.get("stitch"), true);
        int stitchEps = adapter.clamp(adapter.i(op.get("stitchEpsilon"), adapter.i(op.get("stitch_eps"), 0)), 0, 32);
        int stitchSamples = adapter.clamp(adapter.i(op.get("stitchSamples"), adapter.i(op.get("stitch_samples"), -1)), -1, 512);
        String stitchResampleMode = adapter.str(op.get("stitchResampleMode"), adapter.str(op.get("stitch_resample_mode"), "RESAMPLE")).trim().toUpperCase(java.util.Locale.ROOT);
        boolean stitchResample = stitchResampleMode.isBlank() || stitchResampleMode.equals("RESAMPLE") || stitchResampleMode.equals("AUTO");
        BlockState matDef = adapter.pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0xBEEF1101L, Blocks.QUARTZ_BLOCK.getDefaultState());
        int capWidthDef = adapter.clamp(adapter.i(op.get("capWidth"), adapter.i(op.get("cap_width"), 0)), 0, 9);
        BlockState capMatDef = adapter.pick(ctx, op, "capMaterial", "FACADE_TRIM", 0xBEEF1102L, matDef);

        java.util.HashMap<String, PatchData> byId = new java.util.HashMap<>();
        java.util.ArrayList<PatchData> patches = new java.util.ArrayList<>();
        java.util.HashMap<String, SeamRef> seamMap = new java.util.HashMap<>();
        java.util.ArrayList<SeamRef> seamList = new java.util.ArrayList<>();
        for (int pi = 0; pi < patchesList.size(); pi++) {
            Object po = patchesList.get(pi);
            if (!(po instanceof Map<?, ?> pm)) continue;
            String id = adapter.str(pm.get("id"), "P" + pi).trim();

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

            int uN = adapter.clamp(adapter.i(pm.get("uSamples"), adapter.i(pm.get("u"), uDef)), 2, 512);
            int vN = adapter.clamp(adapter.i(pm.get("vSamples"), adapter.i(pm.get("v"), vDef)), 2, 512);
            int thick = adapter.clamp(adapter.i(pm.get("thickness"), thickDef), 1, 9);
            boolean connect = adapter.bool(pm.get("connectSamples"), connectDef);
            BlockState mat = (pm.get("material") != null)
                    ? adapter.pick(ctx, pm, "material", "PRIMARY_STRUCTURE", 0xBEEF1101L ^ id.hashCode(), matDef)
                    : matDef;

            int[][][] grid = AssemblyBezierSurfaceOps.sampleBezierSurface(ctrl, uN, vN);
            PatchData pd = new PatchData(id, uN, vN, thick, mat, grid);
            patches.add(pd);
            byId.put(id, pd);
            for (int iu = 0; iu <= uN; iu++) for (int iv = 0; iv <= vN; iv++) {
                int x = grid[iu][iv][0], y = grid[iu][iv][1], z = grid[iu][iv][2];
                adapter.placePrism(out, ctx, origin, x, y, z, thick, 1, mat);
            }
            if (connect) {
                adapter.connectSurfaceGrid(out, ctx, origin, grid, uN, vN, thick, mat);
            }

            if (stitch) {
                for (Edge e : Edge.values()) {
                    String sig = edgeSignature(pd, e, false);
                    String sigR = edgeSignature(pd, e, true);
                    SeamRef other = seamMap.remove(sigR);
                    if (other == null) {
                        seamMap.put(sig, new SeamRef(pd, e));
                    } else {
                        int seamThick = Math.min(other.patch.thick, pd.thick);
                        stitchEdge(out, ctx, origin, other.patch, other.edge, pd, e, seamThick, matDef, capWidthDef, capMatDef, adapter);
                    }
                    seamList.add(new SeamRef(pd, e));
                }
            }
        }

        if (!stitch) return;

        if (stitchEps > 0 && seamList.size() >= 2) {
            java.util.HashSet<Long> used = new java.util.HashSet<>();
            for (int i = 0; i < seamList.size(); i++) {
                SeamRef a = seamList.get(i);
                long ka = (((long) a.patch.hashCode()) << 8) ^ a.edge.ordinal();
                if (used.contains(ka)) continue;
                int[] a0 = edgePoint(a.patch, a.edge, 0);
                int[] a1 = edgePoint(a.patch, a.edge, edgeCount(a.patch, a.edge) - 1);
                double best = Double.POSITIVE_INFINITY;
                int bestJ = -1;
                boolean bestReverse = false;
                for (int j = i + 1; j < seamList.size(); j++) {
                    SeamRef b = seamList.get(j);
                    long kb = (((long) b.patch.hashCode()) << 8) ^ b.edge.ordinal();
                    if (used.contains(kb)) continue;
                    int[] b0 = edgePoint(b.patch, b.edge, 0);
                    int[] b1 = edgePoint(b.patch, b.edge, edgeCount(b.patch, b.edge) - 1);
                    long eps2 = (long) stitchEps * stitchEps;
                    long d00 = AssemblySeamMathOps.dist2(a0, b0) + AssemblySeamMathOps.dist2(a1, b1);
                    long d01 = AssemblySeamMathOps.dist2(a0, b1) + AssemblySeamMathOps.dist2(a1, b0);
                    boolean rev = d01 < d00;
                    long dGate = Math.min(d00, d01);
                    if (dGate > eps2 * 4L) continue;

                    int nA = edgeCount(a.patch, a.edge);
                    int nB = edgeCount(b.patch, b.edge);
                    int n = (stitchSamples > 0) ? stitchSamples : Math.max(8, Math.min(128, Math.max(nA, nB)));
                    double mse = edgeMse(a.patch, a.edge, b.patch, b.edge, rev, n, stitchResample);
                    if (mse < best) {
                        best = mse;
                        bestJ = j;
                        bestReverse = rev;
                    }
                }
                if (bestJ >= 0) {
                    SeamRef b = seamList.get(bestJ);
                    if (best <= (double) stitchEps * stitchEps) {
                        int seamThick = Math.min(a.patch.thick, b.patch.thick);
                        stitchEdgeResampled(out, ctx, origin, a.patch, a.edge, b.patch, b.edge, bestReverse,
                                seamThick, matDef, (stitchSamples > 0) ? stitchSamples : -1, stitchResample, capWidthDef, capMatDef, adapter);
                        used.add(ka);
                        long kb = (((long) b.patch.hashCode()) << 8) ^ b.edge.ordinal();
                        used.add(kb);
                    }
                }
            }
        }

        Object gridObj = null;
        Object topo = op.get("topology");
        if (topo instanceof Map<?, ?> tm) gridObj = tm.get("grid");
        if (gridObj == null) gridObj = op.get("grid");

        if (topo instanceof Map<?, ?> tm2 && tm2.get("links") instanceof List<?> links) {
            for (int li = 0; li < links.size(); li++) {
                Object lo = links.get(li);
                if (!(lo instanceof Map<?, ?> lm)) continue;
                String aId = adapter.str(lm.get("a"), adapter.str(lm.get("from"), "")).trim();
                String bId = adapter.str(lm.get("b"), adapter.str(lm.get("to"), "")).trim();
                String eaS = adapter.str(lm.get("ea"), adapter.str(lm.get("edgeA"), adapter.str(lm.get("fromEdge"), ""))).trim().toUpperCase(java.util.Locale.ROOT);
                String ebS = adapter.str(lm.get("eb"), adapter.str(lm.get("edgeB"), adapter.str(lm.get("toEdge"), ""))).trim().toUpperCase(java.util.Locale.ROOT);
                PatchData a = resolvePatch(byId, patches, aId);
                PatchData b = resolvePatch(byId, patches, bId);
                if (a == null || b == null) continue;
                Edge ea = parseEdge(eaS);
                Edge eb = parseEdge(ebS);
                if (ea == null || eb == null) continue;

                int linkEps = adapter.clamp(adapter.i(lm.get("epsilon"), adapter.i(lm.get("stitchEpsilon"), stitchEps)), 0, 64);
                int linkSamples = adapter.clamp(adapter.i(lm.get("samples"), adapter.i(lm.get("stitchSamples"), stitchSamples)), -1, 512);
                String linkMode = adapter.str(lm.get("resampleMode"), adapter.str(lm.get("stitchResampleMode"), stitchResampleMode)).trim().toUpperCase(java.util.Locale.ROOT);
                boolean linkResample = linkMode.isBlank() || linkMode.equals("RESAMPLE") || linkMode.equals("AUTO");
                int linkThick = adapter.clamp(adapter.i(lm.get("thickness"), Math.min(a.thick, b.thick)), 1, 9);

                double[] ar = parseRange01(lm.get("aRange"), lm.get("a_range"), lm.get("fromRange"));
                double[] br = parseRange01(lm.get("bRange"), lm.get("b_range"), lm.get("toRange"));
                double a0t = (ar != null) ? ar[0] : 0.0;
                double a1t = (ar != null) ? ar[1] : 1.0;
                double b0t = (br != null) ? br[0] : 0.0;
                double b1t = (br != null) ? br[1] : 1.0;

                int[] a0 = edgePointAtRange(a, ea, 0.0, a0t, a1t);
                int[] a1 = edgePointAtRange(a, ea, 1.0, a0t, a1t);
                int[] b0 = edgePointAtRange(b, eb, 0.0, b0t, b1t);
                int[] b1 = edgePointAtRange(b, eb, 1.0, b0t, b1t);
                boolean reverse = (AssemblySeamMathOps.dist2(a0, b1) + AssemblySeamMathOps.dist2(a1, b0))
                        < (AssemblySeamMathOps.dist2(a0, b0) + AssemblySeamMathOps.dist2(a1, b1));
                if (linkEps > 0) {
                    double n = (linkSamples > 0) ? linkSamples : 64;
                    double mse = edgeMseRange(a, ea, a0t, a1t, b, eb, b0t, b1t, reverse, (int) n, linkResample);
                    if (mse > (double) linkEps * linkEps) continue;
                }
                int linkCapW = adapter.clamp(adapter.i(lm.get("capWidth"), adapter.i(lm.get("cap_width"), capWidthDef)), 0, 9);
                BlockState linkCapMat = (lm.get("capMaterial") != null)
                        ? adapter.pick(ctx, lm, "capMaterial", "FACADE_TRIM", 0xBEEF1202L ^ (li * 1315423911), capMatDef)
                        : capMatDef;
                stitchEdgeResampledRange(out, ctx, origin, a, ea, a0t, a1t, b, eb, b0t, b1t, reverse, linkThick, matDef,
                        (linkSamples > 0) ? linkSamples : -1, linkResample, linkCapW, linkCapMat, adapter);
            }
        }

        if (gridObj instanceof List<?> rows && !rows.isEmpty()) {
            java.util.ArrayList<java.util.ArrayList<String>> ids = new java.util.ArrayList<>();
            for (Object ro : rows) {
                if (!(ro instanceof List<?> row)) continue;
                java.util.ArrayList<String> rr = new java.util.ArrayList<>();
                for (Object cell : row) rr.add(String.valueOf(cell).trim());
                ids.add(rr);
            }
            for (int r = 0; r < ids.size(); r++) {
                for (int c = 0; c < ids.get(r).size(); c++) {
                    String aId = ids.get(r).get(c);
                    PatchData a = resolvePatch(byId, patches, aId);
                    if (a == null) continue;
                    if (c + 1 < ids.get(r).size()) {
                        PatchData b = resolvePatch(byId, patches, ids.get(r).get(c + 1));
                        if (b != null) stitchEdge(out, ctx, origin, a, Edge.U1, b, Edge.U0, Math.min(a.thick, b.thick), matDef, capWidthDef, capMatDef, adapter);
                    }
                    if (r + 1 < ids.size() && c < ids.get(r + 1).size()) {
                        PatchData b = resolvePatch(byId, patches, ids.get(r + 1).get(c));
                        if (b != null) stitchEdge(out, ctx, origin, a, Edge.V1, b, Edge.V0, Math.min(a.thick, b.thick), matDef, capWidthDef, capMatDef, adapter);
                    }
                }
            }
        }
    }

    private enum Edge { U0, U1, V0, V1 }

    private static final class LoftSection {
        final int x, y, z;
        final List<int[]> profile;
        LoftSection(int x, int y, int z, List<int[]> profile) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.profile = profile;
        }
    }

    private static List<int[]> read2DProfilePoints(Object profObj, Adapter adapter) {
        if (profObj == null) return null;
        if (!(profObj instanceof List<?> list)) return null;
        if (!list.isEmpty() && list.getFirst() instanceof List<?>) {
            Object ring0 = list.getFirst();
            if (!(ring0 instanceof List<?> ring)) return null;
            List<int[]> out = new java.util.ArrayList<>();
            for (Object p : ring) {
                if (p instanceof Map<?, ?> pm) {
                    out.add(new int[]{adapter.i(pm.get("x"), 0), adapter.i(pm.get("y"), 0)});
                }
            }
            return out.isEmpty() ? null : out;
        } else {
            List<int[]> out = new java.util.ArrayList<>();
            for (Object p : list) {
                if (p instanceof Map<?, ?> pm) {
                    out.add(new int[]{adapter.i(pm.get("x"), 0), adapter.i(pm.get("y"), 0)});
                }
            }
            return out.isEmpty() ? null : out;
        }
    }

    private static void connectToLast(List<PlannedBlock> out,
                                      MetaAssemblyEngine.Context ctx,
                                      BlockPos origin,
                                      Adapter adapter,
                                      java.util.HashMap<Long, long[]> lastSection,
                                      long uvKey,
                                      int x,
                                      int y,
                                      int z,
                                      BlockState s,
                                      java.util.HashSet<Long> seen,
                                      int connectMaxStep) {
        if (lastSection == null) return;
        if (s == null || s.isAir()) return;

        long[] prev = lastSection.get(uvKey);
        if (prev != null && prev.length >= 3) {
            int x0 = (int) prev[0], y0 = (int) prev[1], z0 = (int) prev[2];
            int dx = x - x0;
            int dy = y - y0;
            int dz = z - z0;
            int dist = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
            if (dist > 1 && dist <= connectMaxStep) {
                for (int i = 1; i < dist; i++) {
                    double t = i / (double) dist;
                    int xi = (int) Math.round(x0 + dx * t);
                    int yi = (int) Math.round(y0 + dy * t);
                    int zi = (int) Math.round(z0 + dz * t);
                    long key = AssemblySeamMathOps.packXYZ(xi, yi, zi);
                    if (seen != null && !seen.add(key)) continue;
                    adapter.put(out, ctx, origin, xi, yi, zi, s);
                }
            }
        }

        lastSection.put(uvKey, new long[]{x, y, z});
    }

    private static int snap(double v, String mode) {
        String m = (mode == null) ? "ROUND" : mode.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (m) {
            case "FLOOR" -> (int) Math.floor(v);
            case "CEIL" -> (int) Math.ceil(v);
            default -> (int) Math.round(v);
        };
    }

    private static final class PatchData {
        @SuppressWarnings("unused")
        final String id;
        final int uN, vN;
        final int thick;
        @SuppressWarnings("unused")
        final BlockState mat;
        final int[][][] grid;
        PatchData(String id, int uN, int vN, int thick, BlockState mat, int[][][] grid) {
            this.id = id;
            this.uN = uN;
            this.vN = vN;
            this.thick = thick;
            this.mat = mat;
            this.grid = grid;
        }
    }

    private static final class SeamRef {
        final PatchData patch;
        final Edge edge;
        SeamRef(PatchData patch, Edge edge) { this.patch = patch; this.edge = edge; }
    }

    private static PatchData resolvePatch(java.util.HashMap<String, PatchData> byId, java.util.ArrayList<PatchData> patches, String key) {
        if (key == null) return null;
        String k = key.trim();
        if (k.isEmpty()) return null;
        PatchData by = byId.get(k);
        if (by != null) return by;
        try {
            int idx = Integer.parseInt(k);
            if (idx >= 0 && idx < patches.size()) return patches.get(idx);
        } catch (Exception e) {
            LOG.debug("patch index lookup failed key={}", k);
        }
        return null;
    }

    private static int[] edgePoint(PatchData p, Edge e, int i) {
        return switch (e) {
            case U0 -> p.grid[0][i];
            case U1 -> p.grid[p.uN][i];
            case V0 -> p.grid[i][0];
            case V1 -> p.grid[i][p.vN];
        };
    }

    private static int edgeCount(PatchData p, Edge e) {
        return (e == Edge.U0 || e == Edge.U1) ? (p.vN + 1) : (p.uN + 1);
    }

    private static String edgeSignature(PatchData p, Edge e, boolean reverse) {
        int n = edgeCount(p, e);
        return AssemblySeamMathOps.edgeSignature(n, reverse, i -> edgePoint(p, e, i));
    }

    private static void stitchEdge(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                   PatchData a, Edge ea, PatchData b, Edge eb, int thick, BlockState mat,
                                   int capWidth, BlockState capMat, Adapter adapter) {
        int nA = edgeCount(a, ea);
        int nB = edgeCount(b, eb);
        AssemblyStitchOps.stitchEdge(
                nA,
                i -> edgePoint(a, ea, i),
                nB,
                i -> edgePoint(b, eb, i),
                thick,
                mat,
                capWidth,
                capMat,
                (x0, y0, z0, x1, y1, z1, thickness, beamH, state) ->
                        adapter.placeBeamLine(out, ctx, origin, x0, y0, z0, x1, y1, z1, thickness, beamH, state),
                (cx, cy, cz, thickness, h, state) ->
                        adapter.placePrism(out, ctx, origin, cx, cy, cz, thickness, h, state)
        );
    }

    private static double edgeMse(PatchData a, Edge ea, PatchData b, Edge eb, boolean reverse, int n, boolean resample) {
        int countA = edgeCount(a, ea);
        int countB = edgeCount(b, eb);
        return AssemblyEdgeSamplingOps.edgeMse(
                countA,
                i -> edgePoint(a, ea, i),
                t -> edgePointAt(a, ea, t),
                countB,
                i -> edgePoint(b, eb, i),
                t -> edgePointAt(b, eb, t),
                reverse,
                n,
                resample
        );
    }

    private static double edgeMseRange(PatchData a, Edge ea, double a0, double a1,
                                       PatchData b, Edge eb, double b0, double b1,
                                       boolean reverse, int n, boolean resample) {
        int countA = edgeCount(a, ea);
        int countB = edgeCount(b, eb);
        return AssemblyEdgeSamplingOps.edgeMseRange(
                i -> edgePoint(a, ea, i),
                t -> edgePointAtRange(a, ea, t, a0, a1),
                countA,
                i -> edgePoint(b, eb, i),
                t -> edgePointAtRange(b, eb, t, b0, b1),
                countB,
                reverse,
                n,
                resample
        );
    }

    private static int[] edgePointAt(PatchData p, Edge e, double t) {
        int n = edgeCount(p, e);
        return AssemblyEdgeSamplingOps.edgePointAt(n, i -> edgePoint(p, e, i), t);
    }

    private static int[] edgePointAtRange(PatchData p, Edge e, double t, double t0, double t1) {
        int n = edgeCount(p, e);
        return AssemblyEdgeSamplingOps.edgePointAtRange(n, i -> edgePoint(p, e, i), t, t0, t1);
    }

    private static void stitchEdgeResampled(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                            PatchData a, Edge ea, PatchData b, Edge eb, boolean reverse,
                                            int thick, BlockState mat, int stitchSamples, boolean resample,
                                            int capWidth, BlockState capMat, Adapter adapter) {
        int nA = edgeCount(a, ea);
        int nB = edgeCount(b, eb);
        AssemblyStitchOps.stitchEdgeResampled(
                nA,
                i -> edgePoint(a, ea, i),
                t -> edgePointAt(a, ea, t),
                nB,
                i -> edgePoint(b, eb, i),
                t -> edgePointAt(b, eb, t),
                reverse,
                thick,
                mat,
                stitchSamples,
                resample,
                capWidth,
                capMat,
                (x0, y0, z0, x1, y1, z1, thickness, beamH, state) ->
                        adapter.placeBeamLine(out, ctx, origin, x0, y0, z0, x1, y1, z1, thickness, beamH, state),
                (cx, cy, cz, thickness, h, state) ->
                        adapter.placePrism(out, ctx, origin, cx, cy, cz, thickness, h, state)
        );
    }

    private static void stitchEdgeResampledRange(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                                 PatchData a, Edge ea, double a0, double a1,
                                                 PatchData b, Edge eb, double b0, double b1,
                                                 boolean reverse,
                                                 int thick, BlockState mat, int stitchSamples, boolean resample,
                                                 int capWidth, BlockState capMat, Adapter adapter) {
        int nA = edgeCount(a, ea);
        int nB = edgeCount(b, eb);
        AssemblyStitchOps.stitchEdgeResampledRange(
                nA,
                i -> edgePoint(a, ea, i),
                t -> edgePointAtRange(a, ea, t, a0, a1),
                nB,
                i -> edgePoint(b, eb, i),
                t -> edgePointAtRange(b, eb, t, b0, b1),
                reverse,
                thick,
                mat,
                stitchSamples,
                resample,
                capWidth,
                capMat,
                (x0, y0, z0, x1, y1, z1, thickness, beamH, state) ->
                        adapter.placeBeamLine(out, ctx, origin, x0, y0, z0, x1, y1, z1, thickness, beamH, state),
                (cx, cy, cz, thickness, h, state) ->
                        adapter.placePrism(out, ctx, origin, cx, cy, cz, thickness, h, state)
        );
    }

    private static Edge parseEdge(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase(java.util.Locale.ROOT);
        if (t.isEmpty()) return null;
        return switch (t) {
            case "U0", "LEFT", "WEST" -> Edge.U0;
            case "U1", "RIGHT", "EAST" -> Edge.U1;
            case "V0", "TOP", "NORTH" -> Edge.V0;
            case "V1", "BOTTOM", "SOUTH" -> Edge.V1;
            default -> null;
        };
    }

    private static double[] parseRange01(Object v0, Object v1, Object v2) {
        Object v = (v0 != null) ? v0 : (v1 != null ? v1 : v2);
        if (v == null) return null;
        if (!(v instanceof List<?> list) || list.size() < 2) return null;
        Double a = doubleOrNull(list.get(0));
        Double b = doubleOrNull(list.get(1));
        if (a == null || b == null) return null;
        return new double[]{a, b};
    }

    private static Double doubleOrNull(Object v) {
        try {
            if (v instanceof Number n) return n.doubleValue();
            if (v != null) return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            LOG.debug("doubleOrNull failed value={}", v);
        }
        return null;
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
