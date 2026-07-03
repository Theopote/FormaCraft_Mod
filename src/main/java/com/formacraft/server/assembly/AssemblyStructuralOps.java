package com.formacraft.server.assembly;

import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Structural skeleton/cable/rib/frame assembly ops extracted from {@link MetaAssemblyEngine}.
 */
public final class AssemblyStructuralOps {
    private AssemblyStructuralOps() {}

    public interface Adapter {
        void placePrism(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, int cx, int cy, int cz, int thickness, int h, BlockState state);
        void placeBeamLine(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, int x0, int y0, int z0, int x1, int y1, int z1, int thickness, int beamH, BlockState state);
        BlockState pick(MetaAssemblyEngine.Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback);
        int i(Object v, int def);
        String str(Object v, String def);
        int clamp(int v, int min, int max);
    }

    public static void applyTruss2D(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, Map<String, Object> op, Adapter adapter) {
        int[] p0 = AssemblyRasterOps.parsePoint(op.get("from"));
        int[] p1 = AssemblyRasterOps.parsePoint(op.get("to"));
        int baseY = Math.min(p0[1], p1[1]);
        int height = adapter.clamp(adapter.i(op.get("height"), adapter.i(op.get("h"), 6)), 1, 64);
        int topY = baseY + height;
        int module = adapter.clamp(adapter.i(op.get("module"), adapter.i(op.get("step"), 4)), 1, 64);
        String pattern = adapter.str(op.get("pattern"), "WARREN").trim().toUpperCase(Locale.ROOT);
        int thick = adapter.clamp(adapter.i(op.get("thickness"), 1), 1, 9);

        BlockState chord = adapter.pick(ctx, op, "chord", "STRUCTURAL_BEAM", 0xA57401L, Blocks.IRON_BARS.getDefaultState());
        BlockState web = adapter.pick(ctx, op, "web", "STRUCTURAL_BEAM", 0xA57402L, chord);
        BlockState joint = adapter.pick(ctx, op, "joint", "STRUCTURAL_BEAM", 0xA57403L, chord);

        BlockPos a = new BlockPos(p0[0], baseY, p0[2]);
        BlockPos b = new BlockPos(p1[0], baseY, p1[2]);
        List<BlockPos> line = AssemblyRasterOps.rasterizeLine2D(a, b, null, false, 0);
        if (line.size() < 2) return;

        for (BlockPos lp : line) {
            adapter.placePrism(out, ctx, origin, lp.getX(), baseY, lp.getZ(), thick, 1, chord);
            adapter.placePrism(out, ctx, origin, lp.getX(), topY, lp.getZ(), thick, 1, chord);
        }

        int n = line.size();
        int lastNode = 0;
        boolean flip = false;
        for (int i = 0; i < n; i += module) {
            int idx = Math.min(i, n - 1);
            BlockPos p = line.get(idx);
            adapter.placePrism(out, ctx, origin, p.getX(), baseY, p.getZ(), thick, 1, joint);
            adapter.placePrism(out, ctx, origin, p.getX(), topY, p.getZ(), thick, 1, joint);
            adapter.placeBeamLine(out, ctx, origin, p.getX(), baseY, p.getZ(), p.getX(), topY, p.getZ(), thick, 1, web);

            if (idx > 0) {
                BlockPos prev = line.get(lastNode);
                if (pattern.contains("PRATT") || pattern.contains("HOWE")) {
                    // P0 keep WARREN-like alternating diagonals.
                }
                if (!flip) {
                    adapter.placeBeamLine(out, ctx, origin, prev.getX(), baseY, prev.getZ(), p.getX(), topY, p.getZ(), thick, 1, web);
                } else {
                    adapter.placeBeamLine(out, ctx, origin, prev.getX(), topY, prev.getZ(), p.getX(), baseY, p.getZ(), thick, 1, web);
                }
                flip = !flip;
                lastNode = idx;
            }
        }
    }

    public static void applyArchRib(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, Map<String, Object> op, Adapter adapter) {
        int[] p0 = AssemblyRasterOps.parsePoint(op.get("from"));
        int[] p1 = AssemblyRasterOps.parsePoint(op.get("to"));
        int thick = adapter.clamp(adapter.i(op.get("thickness"), 1), 1, 9);

        int dx = p1[0] - p0[0];
        int dz = p1[2] - p0[2];
        int dist2d = Math.max(Math.abs(dx), Math.abs(dz));
        int rise = adapter.i(op.get("rise"), adapter.i(op.get("sagitta"), -1));
        if (rise <= 0) rise = adapter.clamp(Math.max(2, dist2d / 6), 2, 48);

        int samples = adapter.i(op.get("samples"), adapter.i(op.get("steps"), -1));
        if (samples <= 0) samples = adapter.clamp(dist2d * 3, 12, 4096);

        BlockState mat = adapter.pick(ctx, op, "material", "STRUCTURAL_BEAM", 0xA57410L, Blocks.IRON_BARS.getDefaultState());

        int lastX = p0[0], lastY = p0[1], lastZ = p0[2];
        boolean first = true;
        for (int si = 0; si <= samples; si++) {
            double t = si / (double) samples;
            double x = p0[0] + (p1[0] - p0[0]) * t;
            double yLine = p0[1] + (p1[1] - p0[1]) * t;
            double z = p0[2] + (p1[2] - p0[2]) * t;
            double y = yLine + (4.0 * rise * t * (1.0 - t));

            int xi = (int) Math.round(x);
            int yi = (int) Math.round(y);
            int zi = (int) Math.round(z);

            if (first) {
                adapter.placePrism(out, ctx, origin, xi, yi, zi, thick, 1, mat);
                first = false;
            } else {
                adapter.placeBeamLine(out, ctx, origin, lastX, lastY, lastZ, xi, yi, zi, thick, 1, mat);
            }
            lastX = xi;
            lastY = yi;
            lastZ = zi;
        }
    }

    public static void applyButtress(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, Map<String, Object> op, Adapter adapter) {
        int[] p0 = AssemblyRasterOps.parsePoint(op.get("from"));
        int[] p1 = AssemblyRasterOps.parsePoint(op.get("to"));

        int thick = adapter.clamp(adapter.i(op.get("thickness"), 1), 1, 9);
        int dx = p1[0] - p0[0];
        int dz = p1[2] - p0[2];
        int dist2d = Math.max(Math.abs(dx), Math.abs(dz));
        int rise = adapter.i(op.get("rise"), adapter.i(op.get("sagitta"), -1));
        if (rise <= 0) rise = adapter.clamp(Math.max(2, dist2d / 6), 2, 48);
        int samples = adapter.i(op.get("samples"), adapter.i(op.get("steps"), -1));
        if (samples <= 0) samples = adapter.clamp(dist2d * 3, 12, 4096);
        int pierDown = adapter.i(op.get("pierDown"), adapter.i(op.get("pier_down"), 6));
        pierDown = adapter.clamp(pierDown, 0, 256);

        BlockState rib = adapter.pick(ctx, op, "rib", "STRUCTURAL_BEAM", 0xA57420L, Blocks.IRON_BARS.getDefaultState());
        BlockState pier = adapter.pick(ctx, op, "pier", "STRUCTURAL_BEAM", 0xA57421L, rib);
        BlockState joint = adapter.pick(ctx, op, "joint", "STRUCTURAL_BEAM", 0xA57422L, rib);

        adapter.placePrism(out, ctx, origin, p0[0], p0[1], p0[2], thick, 1, joint);
        adapter.placePrism(out, ctx, origin, p1[0], p1[1], p1[2], thick, 1, joint);

        int lastX = p0[0], lastY = p0[1], lastZ = p0[2];
        boolean first = true;
        for (int si = 0; si <= samples; si++) {
            double t = si / (double) samples;
            double x = p0[0] + (p1[0] - p0[0]) * t;
            double yLine = p0[1] + (p1[1] - p0[1]) * t;
            double z = p0[2] + (p1[2] - p0[2]) * t;
            double y = yLine + (4.0 * rise * t * (1.0 - t));

            int xi = (int) Math.round(x);
            int yi = (int) Math.round(y);
            int zi = (int) Math.round(z);

            if (first) {
                adapter.placePrism(out, ctx, origin, xi, yi, zi, thick, 1, rib);
                first = false;
            } else {
                adapter.placeBeamLine(out, ctx, origin, lastX, lastY, lastZ, xi, yi, zi, thick, 1, rib);
            }
            lastX = xi;
            lastY = yi;
            lastZ = zi;
        }

        if (pierDown > 0) {
            adapter.placeBeamLine(out, ctx, origin, p1[0], p1[1], p1[2], p1[0], p1[1] - pierDown, p1[2], thick, 1, pier);
        }
    }

    public static void applyTensionCable(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, Map<String, Object> op, Adapter adapter) {
        int[] p0 = AssemblyRasterOps.parsePoint(op.get("from"));
        int[] p1 = AssemblyRasterOps.parsePoint(op.get("to"));

        int thick = adapter.clamp(adapter.i(op.get("thickness"), 1), 1, 5);
        int dx = p1[0] - p0[0];
        int dy = p1[1] - p0[1];
        int dz = p1[2] - p0[2];
        int dist = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));

        int sag = adapter.i(op.get("sag"), adapter.i(op.get("droop"), -1));
        if (sag <= 0) sag = adapter.clamp(Math.max(1, dist / 12), 1, 48);

        int samples = adapter.i(op.get("samples"), adapter.i(op.get("steps"), -1));
        if (samples <= 0) samples = adapter.clamp(dist * 4, 12, 8192);

        BlockState mat = adapter.pick(ctx, op, "material", "STRUCTURAL_CABLE", 0xA57430L,
                adapter.pick(ctx, op, "material", "STRUCTURAL_BEAM", 0xA57431L, Blocks.IRON_BARS.getDefaultState()));

        int hangersEvery = adapter.i(op.get("hangersEvery"), adapter.i(op.get("hangerEvery"), 0));
        int hangersToY = adapter.i(op.get("hangersToY"), adapter.i(op.get("hangerToY"), Integer.MIN_VALUE));
        BlockState hangMat = adapter.pick(ctx, op, "hangersMaterial", "STRUCTURAL_CABLE", 0xA57432L, mat);
        boolean doHangers = hangersEvery > 0 && hangersToY != Integer.MIN_VALUE;

        int cableCount = adapter.clamp(adapter.i(op.get("cableCount"), adapter.i(op.get("count"), 1)), 1, 32);
        int cableSpacing = adapter.clamp(adapter.i(op.get("cableSpacing"), adapter.i(op.get("spacing"), 3)), 1, 64);
        String cableAxis = adapter.str(op.get("cableAxis"), "AUTO").trim().toUpperCase(Locale.ROOT);

        boolean offsetX;
        if ("X".equals(cableAxis)) offsetX = true;
        else if ("Z".equals(cableAxis)) offsetX = false;
        else offsetX = Math.abs(dz) >= Math.abs(dx);

        for (int ci = 0; ci < cableCount; ci++) {
            int center = (cableCount - 1);
            int off = (ci * 2 - center);
            double offAmt = off * (cableSpacing / 2.0);
            int ox = offsetX ? (int) Math.round(offAmt) : 0;
            int oz = offsetX ? 0 : (int) Math.round(offAmt);

            int ax = p0[0] + ox, ay = p0[1], az = p0[2] + oz;
            int bx = p1[0] + ox, by = p1[1], bz = p1[2] + oz;

            int lastX = ax, lastY = ay, lastZ = az;
            boolean first = true;
            for (int si = 0; si <= samples; si++) {
                double t = si / (double) samples;
                double x = ax + (bx - ax) * t;
                double yLine = ay + (by - ay) * t;
                double z = az + (bz - az) * t;
                double y = yLine - (4.0 * sag * t * (1.0 - t));

                int xi = (int) Math.round(x);
                int yi = (int) Math.round(y);
                int zi = (int) Math.round(z);

                if (first) {
                    adapter.placePrism(out, ctx, origin, xi, yi, zi, thick, 1, mat);
                    first = false;
                } else {
                    adapter.placeBeamLine(out, ctx, origin, lastX, lastY, lastZ, xi, yi, zi, thick, 1, mat);
                }

                if (doHangers && si % hangersEvery == 0 && yi > hangersToY) {
                    adapter.placeBeamLine(out, ctx, origin, xi, yi, zi, xi, hangersToY, zi, 1, 1, hangMat);
                }
                lastX = xi;
                lastY = yi;
                lastZ = zi;
            }
        }
    }

    public static void applyFrameGrid3D(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, Map<String, Object> op, Adapter adapter) {
        int x0 = adapter.i(op.get("x0"), 0), x1 = adapter.i(op.get("x1"), 0);
        int y0 = adapter.i(op.get("y0"), 0), y1 = adapter.i(op.get("y1"), 0);
        int z0 = adapter.i(op.get("z0"), 0), z1 = adapter.i(op.get("z1"), 0);
        if (x0 > x1) { int t = x0; x0 = x1; x1 = t; }
        if (y0 > y1) { int t = y0; y0 = y1; y1 = t; }
        if (z0 > z1) { int t = z0; z0 = z1; z1 = t; }

        int stepX = adapter.clamp(adapter.i(op.get("stepX"), adapter.i(op.get("sx"), adapter.i(op.get("step"), 4))), 1, 64);
        int stepY = adapter.clamp(adapter.i(op.get("stepY"), adapter.i(op.get("sy"), adapter.i(op.get("step"), 4))), 1, 64);
        int stepZ = adapter.clamp(adapter.i(op.get("stepZ"), adapter.i(op.get("sz"), adapter.i(op.get("step"), 4))), 1, 64);
        int thick = adapter.clamp(adapter.i(op.get("thickness"), 1), 1, 9);
        String mode = adapter.str(op.get("mode"), "SURFACE").trim().toUpperCase(Locale.ROOT);
        String diagonal = adapter.str(op.get("diagonal"), "NONE").trim().toUpperCase(Locale.ROOT);

        BlockState mat = adapter.pick(ctx, op, "material", "STRUCTURAL_BEAM", 0xA57460L, Blocks.IRON_BARS.getDefaultState());

        List<Integer> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        List<Integer> zs = new ArrayList<>();
        for (int x = x0; x <= x1; x += stepX) xs.add(x);
        if (xs.isEmpty() || xs.getLast() != x1) xs.add(x1);
        for (int y = y0; y <= y1; y += stepY) ys.add(y);
        if (ys.isEmpty() || ys.getLast() != y1) ys.add(y1);
        for (int z = z0; z <= z1; z += stepZ) zs.add(z);
        if (zs.isEmpty() || zs.getLast() != z1) zs.add(z1);

        boolean all = mode.contains("ALL");

        for (int yi : ys) for (int zi : zs) for (int xiIdx = 0; xiIdx + 1 < xs.size(); xiIdx++) {
            int xa = xs.get(xiIdx), xb = xs.get(xiIdx + 1);
            if (!all) {
                boolean onSurface = (yi == y0 || yi == y1) || (zi == z0 || zi == z1);
                if (!onSurface) continue;
            }
            adapter.placeBeamLine(out, ctx, origin, xa, yi, zi, xb, yi, zi, thick, 1, mat);
        }
        for (int yi : ys) for (int xi : xs) for (int ziIdx = 0; ziIdx + 1 < zs.size(); ziIdx++) {
            int za = zs.get(ziIdx), zb = zs.get(ziIdx + 1);
            if (!all) {
                boolean onSurface = (yi == y0 || yi == y1) || (xi == x0 || xi == x1);
                if (!onSurface) continue;
            }
            adapter.placeBeamLine(out, ctx, origin, xi, yi, za, xi, yi, zb, thick, 1, mat);
        }
        for (int zi : zs) for (int xi : xs) for (int yiIdx = 0; yiIdx + 1 < ys.size(); yiIdx++) {
            int ya = ys.get(yiIdx), yb = ys.get(yiIdx + 1);
            if (!all) {
                boolean onSurface = (xi == x0 || xi == x1) || (zi == z0 || zi == z1);
                if (!onSurface) continue;
            }
            adapter.placeBeamLine(out, ctx, origin, xi, ya, zi, xi, yb, zi, thick, 1, mat);
        }

        if (!diagonal.contains("NONE")) {
            boolean faceOnly = diagonal.contains("FACE");
            boolean space = diagonal.contains("SPACE") || diagonal.contains("ALL");

            if (faceOnly || (!space && !diagonal.contains("SPACE"))) {
                for (int zFace : new int[]{z0, z1}) {
                    for (int yiIdx = 0; yiIdx + 1 < ys.size(); yiIdx++) {
                        int ya = ys.get(yiIdx), yb = ys.get(yiIdx + 1);
                        for (int xiIdx = 0; xiIdx + 1 < xs.size(); xiIdx++) {
                            int xa = xs.get(xiIdx), xb = xs.get(xiIdx + 1);
                            if (((xiIdx + yiIdx) & 1) == 0) adapter.placeBeamLine(out, ctx, origin, xa, ya, zFace, xb, yb, zFace, thick, 1, mat);
                            else adapter.placeBeamLine(out, ctx, origin, xb, ya, zFace, xa, yb, zFace, thick, 1, mat);
                        }
                    }
                }
                for (int xFace : new int[]{x0, x1}) {
                    for (int yiIdx = 0; yiIdx + 1 < ys.size(); yiIdx++) {
                        int ya = ys.get(yiIdx), yb = ys.get(yiIdx + 1);
                        for (int ziIdx = 0; ziIdx + 1 < zs.size(); ziIdx++) {
                            int za = zs.get(ziIdx), zb = zs.get(ziIdx + 1);
                            if (((ziIdx + yiIdx) & 1) == 0) adapter.placeBeamLine(out, ctx, origin, xFace, ya, za, xFace, yb, zb, thick, 1, mat);
                            else adapter.placeBeamLine(out, ctx, origin, xFace, ya, zb, xFace, yb, za, thick, 1, mat);
                        }
                    }
                }
                for (int yFace : new int[]{y0, y1}) {
                    for (int ziIdx = 0; ziIdx + 1 < zs.size(); ziIdx++) {
                        int za = zs.get(ziIdx), zb = zs.get(ziIdx + 1);
                        for (int xiIdx = 0; xiIdx + 1 < xs.size(); xiIdx++) {
                            int xa = xs.get(xiIdx), xb = xs.get(xiIdx + 1);
                            if (((xiIdx + ziIdx) & 1) == 0) adapter.placeBeamLine(out, ctx, origin, xa, yFace, za, xb, yFace, zb, thick, 1, mat);
                            else adapter.placeBeamLine(out, ctx, origin, xb, yFace, za, xa, yFace, zb, thick, 1, mat);
                        }
                    }
                }
            }

            if (space) {
                for (int xiIdx = 0; xiIdx + 1 < xs.size(); xiIdx++) {
                    int xa = xs.get(xiIdx), xb = xs.get(xiIdx + 1);
                    for (int yiIdx = 0; yiIdx + 1 < ys.size(); yiIdx++) {
                        int ya = ys.get(yiIdx), yb = ys.get(yiIdx + 1);
                        for (int ziIdx = 0; ziIdx + 1 < zs.size(); ziIdx++) {
                            int za = zs.get(ziIdx), zb = zs.get(ziIdx + 1);
                            if (!all) continue;
                            if (((xiIdx + yiIdx + ziIdx) & 1) == 0) {
                                adapter.placeBeamLine(out, ctx, origin, xa, ya, za, xb, yb, zb, thick, 1, mat);
                            } else {
                                adapter.placeBeamLine(out, ctx, origin, xb, ya, za, xa, yb, zb, thick, 1, mat);
                            }
                        }
                    }
                }
            }
        }
    }
}
