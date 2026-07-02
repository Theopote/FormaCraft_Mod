package com.formacraft.server.assembly;

import com.formacraft.common.logging.FcaLog;
import com.formacraft.server.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Facade-facing Assembly ops extracted from {@link MetaAssemblyEngine}.
 */
public final class AssemblyFacadeOps {
    private AssemblyFacadeOps() {}

    private static final FcaLog LOG = FcaLog.of("AssemblyFacadeOps");

    public interface Adapter {
        void put(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, int x, int y, int z, BlockState state);
        BlockState pick(MetaAssemblyEngine.Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback);
        BlockState parseBlockId(net.minecraft.server.world.ServerWorld world, String id);
        int i(Object v, int def);
        double d(Object v, double def);
        double clamp(double v, double min, double max);
        boolean bool(Object v, boolean def);
        String str(Object v, String def);
        int clamp(int v, int min, int max);
    }

    public static void applySurfacePattern(List<PlannedBlock> out,
                                           MetaAssemblyEngine.Context ctx,
                                           BlockPos origin,
                                           Map<String, Object> op,
                                           Adapter adapter) {
        String face = adapter.str(op.get("face"), "NORTH").trim().toUpperCase(Locale.ROOT);
        String pattern = adapter.str(op.get("pattern"), "GRID").trim().toUpperCase(Locale.ROOT);
        int step = adapter.clamp(adapter.i(op.get("step"), adapter.i(op.get("spacing"), 3)), 1, 16);
        int thick = adapter.clamp(adapter.i(op.get("thickness"), 1), 1, 8);

        int x0 = adapter.i(op.get("x0"), 0), x1 = adapter.i(op.get("x1"), 0);
        int y0 = adapter.i(op.get("y0"), 1), y1 = adapter.i(op.get("y1"), 10);
        int z0 = adapter.i(op.get("z0"), 0), z1 = adapter.i(op.get("z1"), 0);
        int ax0 = Math.min(x0, x1), ax1 = Math.max(x0, x1);
        int ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
        int az0 = Math.min(z0, z1), az1 = Math.max(z0, z1);

        BlockState accent = adapter.pick(ctx, op, "material", "FACADE_ACCENT", 0xA57001L,
                adapter.pick(ctx, op, "accent", "DECOR_DETAIL", 0xA57002L, Blocks.STONE_BRICK_WALL.getDefaultState()));

        BlockState noiseMat = accent;
        if (pattern.equals("NOISE")) {
            Object nmObj = op.get("noiseMaterial");
            if (nmObj == null) nmObj = op.get("noise_material");
            if (nmObj != null) {
                BlockState parsed = adapter.parseBlockId(ctx.world(), String.valueOf(nmObj).trim());
                if (parsed != null) noiseMat = parsed;
                else noiseMat = adapter.pick(ctx, op, "noiseMaterial", "FACADE_ACCENT", 0xA57003L, accent);
            }
        }

        double noiseProb = 0.2;
        if (pattern.equals("NOISE")) {
            Object npObj = op.get("noiseProbability");
            if (npObj == null) npObj = op.get("noise_probability");
            if (npObj != null) {
                try {
                    double v = (npObj instanceof Number n) ? n.doubleValue() : Double.parseDouble(String.valueOf(npObj));
                    noiseProb = adapter.clamp(v, 0.0, 1.0);
                } catch (Exception e) {
                    LOG.debug("parse noise_probability failed value={}", npObj);
                }
            }
        }

        String noiseMethod = "HASH";
        if (pattern.equals("NOISE")) {
            Object nmObj = op.get("noiseMethod");
            if (nmObj == null) nmObj = op.get("noise_method");
            if (nmObj != null) {
                String m = String.valueOf(nmObj).trim().toUpperCase(Locale.ROOT);
                if (m.equals("PERLIN") || m.equals("RANDOM") || m.equals("HASH")) {
                    noiseMethod = m;
                }
            }
        }

        if ("NORTH".equals(face)) {
            applyPatternPlane(out, ctx, origin, pattern, step, thick, accent, noiseMat, noiseProb, noiseMethod, ax0, ax1, ay0, ay1, az0, true, adapter);
        } else if ("SOUTH".equals(face)) {
            applyPatternPlane(out, ctx, origin, pattern, step, thick, accent, noiseMat, noiseProb, noiseMethod, ax0, ax1, ay0, ay1, az1, true, adapter);
        } else if ("WEST".equals(face)) {
            applyPatternPlane(out, ctx, origin, pattern, step, thick, accent, noiseMat, noiseProb, noiseMethod, az0, az1, ay0, ay1, ax0, false, adapter);
        } else if ("EAST".equals(face)) {
            applyPatternPlane(out, ctx, origin, pattern, step, thick, accent, noiseMat, noiseProb, noiseMethod, az0, az1, ay0, ay1, ax1, false, adapter);
        }
    }

    public static void applyFacadeGrid(List<PlannedBlock> out,
                                       MetaAssemblyEngine.Context ctx,
                                       BlockPos origin,
                                       Map<String, Object> op,
                                       Adapter adapter) {
        String face = adapter.str(op.get("face"), adapter.str(op.get("faces"), "NORTH")).trim().toUpperCase(Locale.ROOT);
        int bayW = adapter.clamp(adapter.i(op.get("bayW"), adapter.i(op.get("moduleW"), adapter.i(op.get("gridW"), 3))), 1, 32);
        int bayH = adapter.clamp(adapter.i(op.get("bayH"), adapter.i(op.get("moduleH"), adapter.i(op.get("gridH"), 4))), 1, 32);
        int mullionT = adapter.clamp(adapter.i(op.get("mullionThickness"), adapter.i(op.get("mullionT"), 1)), 0, 8);
        int transomT = adapter.clamp(adapter.i(op.get("transomThickness"), adapter.i(op.get("transomT"), 1)), 0, 8);
        int borderT = adapter.clamp(adapter.i(op.get("borderThickness"), adapter.i(op.get("borderT"), mullionT)), 0, 8);
        int marginU = adapter.clamp(adapter.i(op.get("marginU"), adapter.i(op.get("marginX"), 1)), 0, 64);
        int marginY = adapter.clamp(adapter.i(op.get("marginY"), 1), 0, 64);
        int inset = adapter.clamp(adapter.i(op.get("inset"), 0), 0, 16);
        int depth = adapter.clamp(adapter.i(op.get("depth"), 1), 1, 8);

        int spEvery = adapter.clamp(adapter.i(op.get("spandrelEvery"), adapter.i(op.get("spEvery"), 0)), 0, 128);
        int spH = adapter.clamp(adapter.i(op.get("spandrelHeight"), adapter.i(op.get("spH"), 0)), 0, 64);
        int spOff = adapter.clamp(adapter.i(op.get("spandrelOffset"), adapter.i(op.get("spOffset"), 0)), 0, 128);

        int x0 = adapter.i(op.get("x0"), 0), x1 = adapter.i(op.get("x1"), 0);
        int y0 = adapter.i(op.get("y0"), 1), y1 = adapter.i(op.get("y1"), 10);
        int z0 = adapter.i(op.get("z0"), 0), z1 = adapter.i(op.get("z1"), 0);
        int ax0 = Math.min(x0, x1), ax1 = Math.max(x0, x1);
        int ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
        int az0 = Math.min(z0, z1), az1 = Math.max(z0, z1);

        BlockState frame = adapter.pick(ctx, op, "frame", "FACADE_TRIM", 0xA57201L, Blocks.SMOOTH_STONE.getDefaultState());
        BlockState fill = adapter.pick(ctx, op, "fill", "WINDOW", 0xA57202L, Blocks.GLASS_PANE.getDefaultState());
        BlockState spFill = adapter.pick(ctx, op, "spandrelFill", "WALL_BASE", 0xA57203L, fill);

        for (String f : expandFacesLocal(face)) {
            if ("NORTH".equals(f)) {
                applyFacadeGridPlane(out, ctx, origin, bayW, bayH, mullionT, transomT, borderT, marginU, marginY, frame, fill, spFill,
                        spEvery, spH, spOff, ax0, ax1, ay0, ay1, az0 + inset, true, depth, +1, adapter);
            } else if ("SOUTH".equals(f)) {
                applyFacadeGridPlane(out, ctx, origin, bayW, bayH, mullionT, transomT, borderT, marginU, marginY, frame, fill, spFill,
                        spEvery, spH, spOff, ax0, ax1, ay0, ay1, az1 - inset, true, depth, -1, adapter);
            } else if ("WEST".equals(f)) {
                applyFacadeGridPlane(out, ctx, origin, bayW, bayH, mullionT, transomT, borderT, marginU, marginY, frame, fill, spFill,
                        spEvery, spH, spOff, az0, az1, ay0, ay1, ax0 + inset, false, depth, +1, adapter);
            } else if ("EAST".equals(f)) {
                applyFacadeGridPlane(out, ctx, origin, bayW, bayH, mullionT, transomT, borderT, marginU, marginY, frame, fill, spFill,
                        spEvery, spH, spOff, az0, az1, ay0, ay1, ax1 - inset, false, depth, -1, adapter);
            }
        }
    }

    public static void applySurfaceBands(List<PlannedBlock> out,
                                         MetaAssemblyEngine.Context ctx,
                                         BlockPos origin,
                                         Map<String, Object> op,
                                         Adapter adapter) {
        String face = adapter.str(op.get("face"), adapter.str(op.get("faces"), "NORTH")).trim().toUpperCase(Locale.ROOT);
        int x0 = adapter.i(op.get("x0"), 0), x1 = adapter.i(op.get("x1"), 0);
        int y0 = adapter.i(op.get("y0"), 1), y1 = adapter.i(op.get("y1"), 10);
        int z0 = adapter.i(op.get("z0"), 0), z1 = adapter.i(op.get("z1"), 0);
        int ax0 = Math.min(x0, x1), ax1 = Math.max(x0, x1);
        int ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
        int az0 = Math.min(z0, z1), az1 = Math.max(z0, z1);

        Object hbObj = op.get("horizontalBands");
        if (hbObj == null) hbObj = op.get("hBands");
        if (hbObj == null) hbObj = op.get("bandsH");
        Object vbObj = op.get("verticalBands");
        if (vbObj == null) vbObj = op.get("vBands");
        if (vbObj == null) vbObj = op.get("bandsV");

        for (String f : expandFacesLocal(face)) {
            boolean uIsX = "NORTH".equals(f) || "SOUTH".equals(f);
            int u0 = uIsX ? ax0 : az0;
            int u1 = uIsX ? ax1 : az1;
            int fixed;
            int inwardSign;
            switch (f) {
                case "NORTH" -> {
                    fixed = az0;
                    inwardSign = +1;
                }
                case "SOUTH" -> {
                    fixed = az1;
                    inwardSign = -1;
                }
                case "WEST" -> {
                    fixed = ax0;
                    inwardSign = +1;
                }
                case null, default -> {
                    fixed = ax1;
                    inwardSign = -1;
                }
            }

            if (hbObj instanceof List<?> hbList) {
                for (Object it : hbList) {
                    if (!(it instanceof Map<?, ?> bm)) continue;
                    int by = adapter.i(bm.get("y"), Integer.MIN_VALUE);
                    if (by == Integer.MIN_VALUE) by = adapter.i(bm.get("atY"), Integer.MIN_VALUE);
                    if (by == Integer.MIN_VALUE) continue;
                    int bh = adapter.clamp(adapter.i(bm.get("height"), adapter.i(bm.get("h"), 1)), 1, 32);
                    int inset = adapter.clamp(adapter.i(bm.get("inset"), 0), 0, 16);
                    int outset = adapter.clamp(adapter.i(bm.get("outset"), adapter.i(bm.get("out"), 0)), 0, 16);
                    int depth = adapter.clamp(adapter.i(bm.get("depth"), 1), 1, 8);
                    BlockState mat = adapter.pick(ctx, bm, "material", "FACADE_ACCENT", 0xA57301L, Blocks.STONE_BRICK_SLAB.getDefaultState());

                    int yy0 = Math.max(ay0, by);
                    int yy1 = Math.min(ay1, by + bh - 1);
                    if (yy0 > yy1) continue;
                    applyBandPlane(out, ctx, origin, u0, u1, yy0, yy1, fixed + inwardSign * inset - inwardSign * outset, uIsX, depth, inwardSign, mat, adapter);
                }
            }

            if (vbObj instanceof List<?> vbList) {
                for (Object it : vbList) {
                    if (!(it instanceof Map<?, ?> bm)) continue;
                    int step = adapter.clamp(adapter.i(bm.get("step"), adapter.i(bm.get("spacing"), 4)), 1, 64);
                    int width = adapter.clamp(adapter.i(bm.get("width"), adapter.i(bm.get("thickness"), 1)), 1, 16);
                    int offset = adapter.i(bm.get("offset"), 0);
                    int yy0 = adapter.i(bm.get("y0"), ay0);
                    int yy1 = adapter.i(bm.get("y1"), ay1);
                    yy0 = Math.max(ay0, Math.min(ay1, yy0));
                    yy1 = Math.max(ay0, Math.min(ay1, yy1));
                    if (yy0 > yy1) {
                        int t = yy0;
                        yy0 = yy1;
                        yy1 = t;
                    }
                    int inset = adapter.clamp(adapter.i(bm.get("inset"), 0), 0, 16);
                    int outset = adapter.clamp(adapter.i(bm.get("outset"), adapter.i(bm.get("out"), 0)), 0, 16);
                    int depth = adapter.clamp(adapter.i(bm.get("depth"), 1), 1, 8);
                    BlockState mat = adapter.pick(ctx, bm, "material", "FACADE_TRIM", 0xA57302L, Blocks.SMOOTH_STONE.getDefaultState());

                    applyVerticalBandsPlane(out, ctx, origin, u0, u1, yy0, yy1, fixed + inwardSign * inset - inwardSign * outset, uIsX, depth, inwardSign, step, width, offset, mat, adapter);
                }
            }
        }
    }

    public static void applyOpenings(List<PlannedBlock> out,
                                     MetaAssemblyEngine.Context ctx,
                                     BlockPos origin,
                                     Map<String, Object> op,
                                     Adapter adapter) {
        String face = adapter.str(op.get("face"), "NORTH").trim().toUpperCase(Locale.ROOT);
        String kind = adapter.str(op.get("kind"), "WINDOW_GRID").trim().toUpperCase(Locale.ROOT);

        int x0 = adapter.i(op.get("x0"), 0), x1 = adapter.i(op.get("x1"), 0);
        int y0 = adapter.i(op.get("y0"), 0), y1 = adapter.i(op.get("y1"), 18);
        int z0 = adapter.i(op.get("z0"), 0), z1 = adapter.i(op.get("z1"), 0);
        int ax0 = Math.min(x0, x1), ax1 = Math.max(x0, x1);
        int ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
        int az0 = Math.min(z0, z1), az1 = Math.max(z0, z1);

        BlockState fill = adapter.pick(ctx, op, "fill", "WINDOW", 0xA57101L, Blocks.GLASS_PANE.getDefaultState());
        BlockState frame = adapter.pick(ctx, op, "frame", "FACADE_TRIM", 0xA57102L, Blocks.SMOOTH_STONE.getDefaultState());
        BlockState air = Blocks.AIR.getDefaultState();

        int frameT = adapter.clamp(adapter.i(op.get("frameThickness"), 1), 0, 4);
        int mullionStep = adapter.clamp(adapter.i(op.get("mullionStep"), 0), 0, 16);

        if (kind.contains("DOOR")) {
            int doorW = adapter.clamp(adapter.i(op.get("doorW"), adapter.i(op.get("winW"), 2)), 1, 7);
            int doorH = adapter.clamp(adapter.i(op.get("doorH"), adapter.i(op.get("winH"), 3)), 2, 10);
            int sx = (ax0 + ax1) / 2;
            int sz = (az0 + az1) / 2;
            carveRectOnFace(out, ctx, origin, face, sx, sz, ay0, doorW, doorH, air, frame, frameT, mullionStep, adapter);
            return;
        }

        if (kind.contains("ROSE")) {
            int r = adapter.clamp(adapter.i(op.get("r"), adapter.i(op.get("radius"), 5)), 2, 24);
            int ring = adapter.clamp(adapter.i(op.get("ring"), adapter.i(op.get("frameThickness"), 1)), 1, 6);
            int petals = adapter.clamp(adapter.i(op.get("petals"), adapter.i(op.get("spokes"), 8)), 3, 32);
            int spokeWidth = adapter.clamp(adapter.i(op.get("spokeWidth"), adapter.i(op.get("spokeW"), 1)), 1, 6);
            double phase = adapter.d(op.get("phase"), adapter.d(op.get("phi"), 0.0));
            double spokeThreshold = adapter.d(op.get("spokeThreshold"), adapter.d(op.get("spokeThresh"), 0.06));
            int cy = adapter.i(op.get("centerY"), -999999);
            if (cy <= -999000) cy = ay0 + (ay1 - ay0) * 2 / 3;
            int cx = (ax0 + ax1) / 2;
            int cz = (az0 + az1) / 2;
            if (phase >= 0.0 && phase <= 1.0) phase = phase * (Math.PI * 2.0);
            if (spokeThreshold < 0.0) spokeThreshold = 0.0;
            if (spokeThreshold > 0.25) spokeThreshold = 0.25;
            BlockState innerFill = adapter.pick(ctx, op, "innerFill", "WINDOW", 0xA57111L, fill);
            BlockState spokeMat = adapter.pick(ctx, op, "spokeMaterial", "FACADE_TRIM", 0xA57112L, frame);
            carveRoseOnFace(out, ctx, origin, face, cx, cz, cy, r, ring, petals, phase, spokeWidth, spokeThreshold, fill, innerFill, frame, spokeMat, adapter);
            return;
        }

        int rows = adapter.clamp(adapter.i(op.get("rows"), 2), 1, 12);
        int cols = adapter.clamp(adapter.i(op.get("cols"), 3), 1, 24);
        int winW = adapter.clamp(adapter.i(op.get("winW"), 2), 1, 9);
        int winH = adapter.clamp(adapter.i(op.get("winH"), 3), 1, 12);
        int sillY = adapter.clamp(adapter.i(op.get("sillY"), 2), ay0, ay1);
        int marginX = adapter.clamp(adapter.i(op.get("marginX"), 2), 0, 64);
        int marginY = adapter.clamp(adapter.i(op.get("marginY"), 2), 0, 64);
        int gapX = adapter.clamp(adapter.i(op.get("gapX"), 2), 0, 32);
        int gapY = adapter.clamp(adapter.i(op.get("gapY"), 2), 0, 32);

        boolean spanAlongX = "NORTH".equals(face) || "SOUTH".equals(face);
        int spanMin = spanAlongX ? ax0 : az0;
        int spanMax = spanAlongX ? ax1 : az1;
        int span = spanMax - spanMin + 1;
        int usable = Math.max(0, span - marginX * 2);
        int totalW = cols * winW + Math.max(0, cols - 1) * gapX;
        if (totalW <= 0) return;
        if (totalW > usable) {
            cols = Math.max(1, (usable + gapX) / (winW + gapX));
            totalW = cols * winW + Math.max(0, cols - 1) * gapX;
        }
        int start = spanMin + marginX + Math.max(0, (usable - totalW) / 2);

        int usableY = Math.max(0, (ay1 - ay0 + 1) - marginY * 2);
        int totalH = rows * winH + Math.max(0, rows - 1) * gapY;
        if (totalH > usableY) {
            rows = Math.max(1, (usableY + gapY) / (winH + gapY));
            totalH = rows * winH + Math.max(0, rows - 1) * gapY;
        }
        int startY = Math.min(ay1 - totalH, Math.max(ay0 + marginY, sillY));

        for (int ry = 0; ry < rows; ry++) {
            int yBase = startY + ry * (winH + gapY);
            for (int cx = 0; cx < cols; cx++) {
                int off = start + cx * (winW + gapX);
                int centerX = spanAlongX ? (off + winW / 2) : ((ax0 + ax1) / 2);
                int centerZ = spanAlongX ? ((az0 + az1) / 2) : (off + winW / 2);
                if (kind.contains("ARCH")) {
                    String archType = adapter.str(op.get("archType"), adapter.str(op.get("arch"), "ROUND")).trim().toUpperCase(Locale.ROOT);
                    int archThickness = adapter.clamp(adapter.i(op.get("archThickness"), adapter.i(op.get("archT"), frameT)), 0, 6);
                    BlockState keystone = adapter.pick(ctx, op, "keystone", "FACADE_TRIM", 0xA57121L, frame);
                    boolean keystoneOn = adapter.bool(op.get("keystoneOn"), true);
                    String tracery = adapter.str(op.get("tracery"), adapter.str(op.get("traceryType"), "")).trim().toUpperCase(Locale.ROOT);
                    int traceryThickness = adapter.clamp(adapter.i(op.get("traceryThickness"), adapter.i(op.get("traceryT"), 1)), 0, 6);
                    int traceryY = adapter.i(op.get("traceryY"), Integer.MIN_VALUE);
                    int traceryInset = adapter.clamp(adapter.i(op.get("traceryInset"), 0), 0, 2);
                    int foilRadius = adapter.i(op.get("traceryFoilRadius"), adapter.i(op.get("foilRadius"), 0));
                    int foilCount = resolveFoilCount(op, winH, adapter);
                    int foilStepY = adapter.i(op.get("traceryFoilStepY"), adapter.i(op.get("foilStepY"), adapter.i(op.get("foilGapY"), 0)));
                    boolean foilStepAuto = AssemblyValueParser.isAuto(op.get("foilStepY"))
                            || AssemblyValueParser.isAuto(op.get("foilGapY"))
                            || AssemblyValueParser.isAuto(op.get("traceryFoilStepY"));
                    BlockState traceryMat = adapter.pick(ctx, op, "traceryMaterial", "FACADE_TRIM", 0xA57122L, frame);
                    int foilCenterY = resolveFoilCenterY(op, yBase, winH, adapter);
                    carveArchOnFace(out, ctx, origin, face, centerX, centerZ, yBase, winW, winH, archType, fill, frame, frameT, mullionStep,
                            archThickness, keystoneOn ? keystone : null, tracery, traceryMat, traceryThickness, traceryY, traceryInset,
                            foilRadius, foilCenterY, foilCount, foilStepY, foilStepAuto, adapter);
                } else {
                    carveRectOnFace(out, ctx, origin, face, centerX, centerZ, yBase, winW, winH, fill, frame, frameT, mullionStep, adapter);
                }
            }
        }
    }

    private static void applyPatternPlane(List<PlannedBlock> out,
                                          MetaAssemblyEngine.Context ctx,
                                          BlockPos origin,
                                          String pattern,
                                          int step,
                                          int thick,
                                          BlockState accent,
                                          BlockState noiseMat,
                                          double noiseProb,
                                          String noiseMethod,
                                          int u0,
                                          int u1,
                                          int y0,
                                          int y1,
                                          int fixed,
                                          boolean uIsX,
                                          Adapter adapter) {
        int au0 = Math.min(u0, u1), au1 = Math.max(u0, u1);
        int ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
        for (int y = ay0; y <= ay1; y++) {
            for (int u = au0; u <= au1; u++) {
                boolean place;
                BlockState mat = accent;

                switch (pattern) {
                    case "STRIPES_V", "STRIPES_VERTICAL" -> place = (Math.floorMod(u, step) == 0);
                    case "STRIPES_H", "STRIPES_HORIZONTAL" -> place = (Math.floorMod(y, step) == 0);
                    case "RIBS_V", "RIBS_VERTICAL" -> place = (Math.floorMod(u, step) < thick);
                    case "RIBS_H", "RIBS_HORIZONTAL" -> place = (Math.floorMod(y, step) < thick);
                    case "NOISE" -> {
                        double noiseValue;
                        int x = uIsX ? u : fixed;
                        int z = uIsX ? fixed : u;
                        long seed = (long) x * 73856093L ^ (long) y * 19349663L ^ (long) z * 83492791L;

                        if ("PERLIN".equals(noiseMethod)) {
                            noiseValue = simplePerlinNoise(x, y, z, seed);
                        } else if ("RANDOM".equals(noiseMethod)) {
                            java.util.Random rng = new java.util.Random(seed);
                            noiseValue = rng.nextDouble();
                        } else {
                            noiseValue = hashNoise(x, y, z, seed);
                        }

                        place = (noiseValue < noiseProb);
                        if (place) mat = noiseMat;
                    }
                    default -> place = (Math.floorMod(u, step) == 0) || (Math.floorMod(y, step) == 0);
                }

                if (!place) continue;
                int x = uIsX ? u : fixed;
                int z = uIsX ? fixed : u;
                adapter.put(out, ctx, origin, x, y, z, mat);
            }
        }
    }

    private static double hashNoise(int x, int y, int z, long seed) {
        long h = seed;
        h ^= (long) x * 73856093L;
        h ^= (long) y * 19349663L;
        h ^= (long) z * 83492791L;
        h = h * (h * h * 15731L + 789221L) + 1376312589L;
        h = h ^ (h >>> 16);
        return ((h & 0x7FFFFFFFL) % 10000) / 10000.0;
    }

    private static double simplePerlinNoise(int x, int y, int z, long seed) {
        double fx = x * 0.1;
        double fy = y * 0.1;
        double fz = z * 0.1;

        int ix = (int) Math.floor(fx);
        int iy = (int) Math.floor(fy);
        int iz = (int) Math.floor(fz);

        double fx0 = fx - ix;
        double fy0 = fy - iy;
        double fz0 = fz - iz;

        double ux = fx0 * fx0 * (3.0 - 2.0 * fx0);
        double uy = fy0 * fy0 * (3.0 - 2.0 * fy0);
        double uz = fz0 * fz0 * (3.0 - 2.0 * fz0);

        double n000 = hashNoise(ix, iy, iz, seed);
        double n100 = hashNoise(ix + 1, iy, iz, seed);
        double n010 = hashNoise(ix, iy + 1, iz, seed);
        double n110 = hashNoise(ix + 1, iy + 1, iz, seed);
        double n001 = hashNoise(ix, iy, iz + 1, seed);
        double n101 = hashNoise(ix + 1, iy, iz + 1, seed);
        double n011 = hashNoise(ix, iy + 1, iz + 1, seed);
        double n111 = hashNoise(ix + 1, iy + 1, iz + 1, seed);

        double nx00 = AssemblyBezierOps.lerp(n000, n100, ux);
        double nx10 = AssemblyBezierOps.lerp(n010, n110, ux);
        double nx01 = AssemblyBezierOps.lerp(n001, n101, ux);
        double nx11 = AssemblyBezierOps.lerp(n011, n111, ux);
        double nxy0 = AssemblyBezierOps.lerp(nx00, nx10, uy);
        double nxy1 = AssemblyBezierOps.lerp(nx01, nx11, uy);
        return AssemblyBezierOps.lerp(nxy0, nxy1, uz);
    }

    private static void applyFacadeGridPlane(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                             int bayW, int bayH, int mullionT, int transomT, int borderT, int marginU, int marginY,
                                             BlockState frame, BlockState fill, BlockState spandrelFill,
                                             int spandrelEvery, int spandrelHeight, int spandrelOffset,
                                             int u0, int u1, int y0, int y1, int fixed, boolean uIsX, int depth, int inwardSign,
                                             Adapter adapter) {
        int au0 = Math.min(u0, u1), au1 = Math.max(u0, u1);
        int ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
        int innerU0 = au0 + marginU;
        int innerU1 = au1 - marginU;
        int innerY0 = ay0 + marginY;
        int innerY1 = ay1 - marginY;
        if (innerU0 > innerU1 || innerY0 > innerY1) return;

        for (int y = innerY0; y <= innerY1; y++) {
            int ly = y - innerY0;
            for (int u = innerU0; u <= innerU1; u++) {
                int lu = u - innerU0;

                boolean isBorder = false;
                if (borderT > 0) {
                    if (u - innerU0 < borderT || innerU1 - u < borderT) isBorder = true;
                    if (y - innerY0 < borderT || innerY1 - y < borderT) isBorder = true;
                }

                boolean isMullion = (mullionT > 0) && (Math.floorMod(lu, bayW) < mullionT);
                boolean isTransom = (transomT > 0) && (Math.floorMod(ly, bayH) < transomT);

                boolean isSpandrel = false;
                if (spandrelEvery > 0 && spandrelHeight > 0) {
                    int m = Math.floorMod((y - innerY0) - spandrelOffset, spandrelEvery);
                    isSpandrel = m < spandrelHeight;
                }
                BlockState panel = isSpandrel ? spandrelFill : fill;
                BlockState s = (isBorder || isMullion || isTransom) ? frame : panel;

                for (int k = 0; k < depth; k++) {
                    int x = uIsX ? u : (fixed + inwardSign * k);
                    int z = uIsX ? (fixed + inwardSign * k) : u;
                    adapter.put(out, ctx, origin, x, y, z, s);
                }
            }
        }
    }

    private static void applyBandPlane(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                       int u0, int u1, int y0, int y1, int fixed, boolean uIsX, int depth, int inwardSign,
                                       BlockState mat, Adapter adapter) {
        int au0 = Math.min(u0, u1), au1 = Math.max(u0, u1);
        int ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
        for (int y = ay0; y <= ay1; y++) {
            for (int u = au0; u <= au1; u++) {
                for (int k = 0; k < depth; k++) {
                    int x = uIsX ? u : (fixed + inwardSign * k);
                    int z = uIsX ? (fixed + inwardSign * k) : u;
                    adapter.put(out, ctx, origin, x, y, z, mat);
                }
            }
        }
    }

    private static void applyVerticalBandsPlane(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                                int u0, int u1, int y0, int y1, int fixed, boolean uIsX, int depth, int inwardSign,
                                                int step, int width, int offset, BlockState mat, Adapter adapter) {
        int au0 = Math.min(u0, u1), au1 = Math.max(u0, u1);
        int ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
        for (int y = ay0; y <= ay1; y++) {
            for (int u = au0; u <= au1; u++) {
                int lu = u - au0 - offset;
                if (Math.floorMod(lu, step) >= width) continue;
                for (int k = 0; k < depth; k++) {
                    int x = uIsX ? u : (fixed + inwardSign * k);
                    int z = uIsX ? (fixed + inwardSign * k) : u;
                    adapter.put(out, ctx, origin, x, y, z, mat);
                }
            }
        }
    }

    private static List<String> expandFacesLocal(String faces) {
        if (faces == null) return List.of("NORTH", "SOUTH", "EAST", "WEST");
        String f = faces.trim().toUpperCase(Locale.ROOT);
        if (f.isBlank() || f.equals("ALL") || f.equals("*")) return List.of("NORTH", "SOUTH", "EAST", "WEST");
        if (f.contains(",")) {
            ArrayList<String> out = new ArrayList<>();
            for (String s : f.split(",")) {
                String x = s.trim().toUpperCase(Locale.ROOT);
                if (x.isBlank()) continue;
                if (x.equals("ALL") || x.equals("*")) return List.of("NORTH", "SOUTH", "EAST", "WEST");
                out.add(x);
            }
            return out.isEmpty() ? List.of("NORTH", "SOUTH", "EAST", "WEST") : out;
        }
        return List.of(f);
    }

    private static void carveRectOnFace(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                        String face, int centerX, int centerZ, int yBase, int rectW, int rectH,
                                        BlockState fill, BlockState frame, int frameT, int mullionStep, Adapter adapter) {
        int hw = Math.max(0, rectW / 2);
        int y1 = yBase + rectH - 1;

        boolean alongX = "NORTH".equals(face) || "SOUTH".equals(face);
        for (int y = yBase; y <= y1; y++) {
            for (int du = -hw; du <= hw; du++) {
                int x = alongX ? (centerX + du) : centerX;
                int z = alongX ? centerZ : (centerZ + du);
                boolean isFrame = false;
                if (frameT > 0) {
                    if (y - yBase < frameT || y1 - y < frameT) isFrame = true;
                    if (du + hw < frameT || hw - du < frameT) isFrame = true;
                }
                boolean isMullion = (mullionStep > 0) && (Math.floorMod(du + hw, mullionStep) == 0);
                BlockState s = (isFrame || isMullion) ? frame : fill;
                adapter.put(out, ctx, origin, x, y, z, s);
            }
        }
    }

    private static void carveArchOnFace(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                        String face, int centerX, int centerZ, int yBase, int rectW, int rectH,
                                        String archType, BlockState fill, BlockState frame, int frameT, int mullionStep,
                                        int archThickness, BlockState keystone, String tracery, BlockState traceryMat,
                                        int traceryThickness, int traceryYAbs, int traceryInset, int foilRadius,
                                        int foilCenterYAbs, int foilCount, int foilStepY, boolean foilStepAuto,
                                        Adapter adapter) {
        int hw = Math.max(1, rectW / 2);
        int archRise = Math.max(2, Math.min(rectH - 1, hw));
        int baseH = Math.max(1, rectH - archRise);
        int y1 = yBase + rectH - 1;

        boolean alongX = "NORTH".equals(face) || "SOUTH".equals(face);
        double pr = Math.max(hw + 1, Math.round(hw * 1.6));
        double pr2 = pr * pr;

        for (int y = yBase; y <= y1; y++) {
            int localY = y - yBase;
            for (int du = -hw; du <= hw; du++) {
                boolean inside;
                if (localY < baseH) {
                    inside = true;
                } else {
                    int ay = localY - baseH;
                    if (archType != null && archType.contains("POINT")) {
                        double d1 = (du + hw) * (du + hw) + (double) ay * ay;
                        double d2 = (du - hw) * (du - hw) + (double) ay * ay;
                        inside = (d1 <= pr2) && (d2 <= pr2);
                    } else {
                        inside = (du * du + ay * ay) <= (hw * hw);
                    }
                }
                if (!inside) continue;

                boolean isFrame = false;
                if (frameT > 0) {
                    for (int k = 0; k < frameT && !isFrame; k++) {
                        int[] nu = new int[]{du + k, du - k, du, du};
                        int[] ny = new int[]{y + k, y - k, y, y};
                        for (int j = 0; j < 4; j++) {
                            int ndu = nu[j];
                            int nyy = ny[j];
                            int lyy = nyy - yBase;
                            boolean nInside;
                            if (lyy < 0 || lyy >= rectH) nInside = false;
                            else if (lyy < baseH) nInside = true;
                            else {
                                int nay = lyy - baseH;
                                if (archType != null && archType.contains("POINT")) {
                                    double d1 = (ndu + hw) * (ndu + hw) + (double) nay * nay;
                                    double d2 = (ndu - hw) * (ndu - hw) + (double) nay * nay;
                                    nInside = (d1 <= pr2) && (d2 <= pr2);
                                } else {
                                    nInside = (ndu * ndu + nay * nay) <= (hw * hw);
                                }
                            }
                            if (!nInside) {
                                isFrame = true;
                                break;
                            }
                        }
                    }
                }
                boolean isMullion = (mullionStep > 0) && (Math.floorMod(du + hw, mullionStep) == 0);
                if (!isFrame && archThickness > 0) {
                    int duAbs = Math.abs(du);
                    if (duAbs >= hw - (archThickness - 1)) isFrame = true;
                    if ((y - yBase) < archThickness || (y1 - y) < archThickness) isFrame = true;
                }

                BlockState s = (isFrame || isMullion) ? frame : fill;
                if (!isFrame && traceryMat != null && traceryThickness > 0 && tracery != null && !tracery.isBlank()) {
                    int ty = (traceryYAbs != Integer.MIN_VALUE) ? traceryYAbs : (yBase + baseH + (rectH - baseH) / 2);
                    boolean on = false;
                    List<String> parts = splitTracery(tracery);
                    for (String part : parts) {
                        if (part.isBlank()) continue;
                        if (part.contains("CROSS")) {
                            if (Math.abs(du) < traceryThickness) on = true;
                            if (Math.abs(y - ty) < traceryThickness) on = true;
                        } else if (part.contains("QUATRE")) {
                            int baseCy = (foilCenterYAbs != Integer.MIN_VALUE) ? foilCenterYAbs
                                    : ((traceryYAbs != Integer.MIN_VALUE) ? traceryYAbs : (y1 - Math.max(2, archRise / 2)));
                            int cx0 = 0;
                            int rr = (foilRadius > 0) ? foilRadius : Math.max(2, hw / 3);
                            int rr2 = rr * rr;
                            int stepY = (foilStepY > 0) ? foilStepY : (rr * 2 + 1);
                            if (foilStepAuto) stepY = Math.max(stepY, Math.max(3, traceryThickness * 2 + 2));
                            int n = Math.max(1, foilCount);
                            int cy0 = baseCy - (n - 1) * stepY / 2;
                            for (int ii = 0; ii < n && !on; ii++) {
                                int cy = cy0 + ii * stepY;
                                int[][] centers = new int[][]{{cx0 - rr, cy}, {cx0 + rr, cy}, {cx0, cy - rr}, {cx0, cy + rr}};
                                for (int[] c : centers) {
                                    int dx = du - c[0];
                                    int dy = y - c[1];
                                    if (dx * dx + dy * dy <= rr2) {
                                        on = true;
                                        break;
                                    }
                                }
                            }
                        } else if (part.contains("TRE")) {
                            int baseCy = (foilCenterYAbs != Integer.MIN_VALUE) ? foilCenterYAbs
                                    : ((traceryYAbs != Integer.MIN_VALUE) ? traceryYAbs : (y1 - Math.max(2, archRise / 2)));
                            int cx0 = 0;
                            int rr = (foilRadius > 0) ? foilRadius : Math.max(2, hw / 3);
                            int rr2 = rr * rr;
                            int stepY = (foilStepY > 0) ? foilStepY : (rr * 2 + 1);
                            if (foilStepAuto) stepY = Math.max(stepY, Math.max(3, traceryThickness * 2 + 2));
                            int n = Math.max(1, foilCount);
                            int cy0 = baseCy - (n - 1) * stepY / 2;
                            for (int ii = 0; ii < n && !on; ii++) {
                                int cy = cy0 + ii * stepY;
                                int[][] centers = new int[][]{{cx0 - rr, cy}, {cx0 + rr, cy}, {cx0, cy + rr}};
                                for (int[] c : centers) {
                                    int dx = du - c[0];
                                    int dy = y - c[1];
                                    if (dx * dx + dy * dy <= rr2) {
                                        on = true;
                                        break;
                                    }
                                }
                            }
                        }
                        if (on) break;
                    }
                    if (on) s = traceryMat;
                }
                if (keystone != null && du == 0 && y == y1) s = keystone;

                int x = alongX ? (centerX + du) : centerX;
                int z = alongX ? centerZ : (centerZ + du);
                if (traceryInset > 0 && s == traceryMat) {
                    int[] p = insetOnFace(face, x, z, traceryInset);
                    x = p[0];
                    z = p[1];
                }
                adapter.put(out, ctx, origin, x, y, z, s);
            }
        }
    }

    private static List<String> splitTracery(String tracery) {
        if (tracery == null) return List.of();
        String t = tracery.trim().toUpperCase(Locale.ROOT);
        if (t.isBlank()) return List.of();
        t = t.replace('|', '+').replace(',', '+').replace(' ', '+');
        String[] parts = t.split("\\+");
        ArrayList<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null) continue;
            String s = p.trim().toUpperCase(Locale.ROOT);
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    private static int[] insetOnFace(String face, int x, int z, int inset) {
        if (inset <= 0) return new int[]{x, z};
        String f = (face == null) ? "" : face.trim().toUpperCase(Locale.ROOT);
        return switch (f) {
            case "NORTH" -> new int[]{x, z + inset};
            case "SOUTH" -> new int[]{x, z - inset};
            case "WEST" -> new int[]{x + inset, z};
            case "EAST" -> new int[]{x - inset, z};
            default -> new int[]{x, z};
        };
    }

    private static int resolveFoilCount(Map<String, Object> op, int winH, Adapter adapter) {
        Object rawA = op == null ? null : op.get("foilCount");
        Object rawB = op == null ? null : op.get("traceryFoilCount");

        if (AssemblyValueParser.isAuto(rawA) || AssemblyValueParser.isAuto(rawB)) {
            int h = Math.max(0, winH);
            int n = (h >= 11) ? 3 : (h >= 8) ? 2 : 1;
            return adapter.clamp(n, 1, 8);
        }

        Integer v = AssemblyValueParser.asInt(rawA);
        if (v == null) v = AssemblyValueParser.asInt(rawB);
        if (v == null) v = adapter.i(op == null ? null : op.get("traceryFoilCount"), adapter.i(op == null ? null : op.get("foilCount"), 1));
        return adapter.clamp(v, 1, 8);
    }

    private static int resolveFoilCenterY(Map<String, Object> op, int yBase, int winH, Adapter adapter) {
        Object rawA = op == null ? null : op.get("foilCenterY");
        Object rawB = op == null ? null : op.get("traceryFoilCenterY");

        if (AssemblyValueParser.isAuto(rawA) || AssemblyValueParser.isAuto(rawB)) {
            int h = Math.max(0, winH);
            return yBase + Math.max(2, (h * 2) / 3);
        }

        Integer v = AssemblyValueParser.asInt(rawA);
        if (v == null) v = AssemblyValueParser.asInt(rawB);
        if (v != null) return v;

        return adapter.i(op == null ? null : op.get("traceryFoilCenterY"), adapter.i(op == null ? null : op.get("foilCenterY"), Integer.MIN_VALUE));
    }

    private static void carveRoseOnFace(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                        String face, int centerX, int centerZ, int centerY,
                                        int r, int ring, int petals, double phase, int spokeWidth, double spokeThreshold,
                                        BlockState fill, BlockState innerFill, BlockState frame, BlockState spokeMat,
                                        Adapter adapter) {
        boolean alongX = "NORTH".equals(face) || "SOUTH".equals(face);
        int r2 = r * r;
        int inner = Math.max(0, r - ring);
        int inner2 = inner * inner;

        for (int dy = -r; dy <= r; dy++) {
            for (int du = -r; du <= r; du++) {
                int d2 = du * du + dy * dy;
                if (d2 > r2) continue;

                boolean isRing = d2 >= inner2;
                boolean isCore = (Math.abs(du) <= spokeWidth && Math.abs(dy) <= spokeWidth);

                boolean isSpoke = false;
                if (petals > 0 && d2 > 1) {
                    double ang = Math.atan2(dy, du) + phase;
                    if (ang < 0) ang += Math.PI * 2.0;
                    if (ang >= Math.PI * 2.0) ang -= Math.PI * 2.0;
                    double bin = (ang / (Math.PI * 2.0)) * petals;
                    double frac = bin - Math.floor(bin);
                    if (frac < spokeThreshold || frac > (1.0 - spokeThreshold)) isSpoke = true;
                    if (spokeWidth > 1) {
                        double t = Math.min(frac, 1.0 - frac);
                        if (t < (spokeThreshold * spokeWidth)) isSpoke = true;
                    }
                }

                BlockState s = isRing ? frame : (isSpoke ? spokeMat : (isCore ? innerFill : fill));
                int x = alongX ? (centerX + du) : centerX;
                int z = alongX ? centerZ : (centerZ + du);
                int y = centerY + dy;
                adapter.put(out, ctx, origin, x, y, z, s);
            }
        }
    }
}
