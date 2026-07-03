package com.formacraft.server.assembly;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MetaAssemblyEngine (v1):
 * Executes an AssemblySpec ops list into PlannedBlocks.
 * <p>
 * This is intentionally conservative: opt-in only (extra.assembly).
 */
public final class MetaAssemblyEngine {
    public record Context(ServerWorld world,
                          BlockPos origin,
                          Direction entranceFacing,
                          String paletteId) {}

    private static final AssemblyFacadeOps.Adapter FACADE_OPS_ADAPTER = new AssemblyFacadeOps.Adapter() {
        @Override
        public void put(List<PlannedBlock> out, Context ctx, BlockPos origin, int x, int y, int z, BlockState state) {
            MetaAssemblyEngine.put(out, ctx, origin, x, y, z, state);
        }

        @Override
        public BlockState pick(Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback) {
            return MetaAssemblyEngine.pick(ctx, op, overrideKey, semanticKey, salt, fallback);
        }

        @Override
        public BlockState parseBlockId(ServerWorld world, String id) {
            return MetaAssemblyEngine.parseBlockId(world, id);
        }

        @Override
        public int i(Object v, int def) {
            return MetaAssemblyEngine.i(v, def);
        }

        @Override
        public double d(Object v, double def) {
            return MetaAssemblyEngine.d(v, def);
        }

        @Override
        public double clamp(double v, double min, double max) {
            return MetaAssemblyEngine.clamp(v, min, max);
        }

        @Override
        public boolean bool(Object v, boolean def) {
            return MetaAssemblyEngine.bool(v, def);
        }

        @Override
        public String str(Object v, String def) {
            return MetaAssemblyEngine.str(v, def);
        }

        @Override
        public int clamp(int v, int min, int max) {
            return MetaAssemblyEngine.clamp(v, min, max);
        }
    };

    private static final AssemblySurfaceOps.Adapter SURFACE_OPS_ADAPTER = new AssemblySurfaceOps.Adapter() {
        @Override
        public void put(List<PlannedBlock> out, Context ctx, BlockPos origin, int x, int y, int z, BlockState state) {
            MetaAssemblyEngine.put(out, ctx, origin, x, y, z, state);
        }

        @Override
        public void placePrism(List<PlannedBlock> out, Context ctx, BlockPos origin, int cx, int cy, int cz, int thickness, int h, BlockState state) {
            MetaAssemblyEngine.placePrism(out, ctx, origin, cx, cy, cz, thickness, h, state);
        }

        @Override
        public void placeBeamLine(List<PlannedBlock> out, Context ctx, BlockPos origin, int x0, int y0, int z0, int x1, int y1, int z1, int thickness, int beamH, BlockState state) {
            MetaAssemblyEngine.placeBeamLine(out, ctx, origin, x0, y0, z0, x1, y1, z1, thickness, beamH, state);
        }

        @Override
        public void connectSurfaceGrid(List<PlannedBlock> out, Context ctx, BlockPos origin, int[][][] grid, int uN, int vN, int thick, BlockState mat) {
            MetaAssemblyEngine.connectSurfaceGrid(out, ctx, origin, grid, uN, vN, thick, mat);
        }

        @Override
        public BlockState pick(Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback) {
            return MetaAssemblyEngine.pick(ctx, op, overrideKey, semanticKey, salt, fallback);
        }

        @Override
        public int i(Object v, int def) {
            return MetaAssemblyEngine.i(v, def);
        }

        @Override
        public double d(Object v, double def) {
            return MetaAssemblyEngine.d(v, def);
        }

        @Override
        public boolean bool(Object v, boolean def) {
            return MetaAssemblyEngine.bool(v, def);
        }

        @Override
        public String str(Object v, String def) {
            return MetaAssemblyEngine.str(v, def);
        }

        @Override
        public int clamp(int v, int min, int max) {
            return MetaAssemblyEngine.clamp(v, min, max);
        }
    };

    private static final AssemblyIsoSurfaceOps.Adapter ISO_SURFACE_OPS_ADAPTER = new AssemblyIsoSurfaceOps.Adapter() {
        @Override
        public void put(List<PlannedBlock> out, Context ctx, BlockPos origin, int x, int y, int z, BlockState state) {
            MetaAssemblyEngine.put(out, ctx, origin, x, y, z, state);
        }

        @Override
        public BlockState pick(Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback) {
            return MetaAssemblyEngine.pick(ctx, op, overrideKey, semanticKey, salt, fallback);
        }

        @Override
        public int i(Object v, int def) {
            return MetaAssemblyEngine.i(v, def);
        }

        @Override
        public double d(Object v, double def) {
            return MetaAssemblyEngine.d(v, def);
        }

        @Override
        public String str(Object v, String def) {
            return MetaAssemblyEngine.str(v, def);
        }

        @Override
        public int clamp(int v, int min, int max) {
            return MetaAssemblyEngine.clamp(v, min, max);
        }
    };

    private static final AssemblySolidOps.Adapter SOLID_OPS_ADAPTER = new AssemblySolidOps.Adapter() {
        @Override
        public void put(List<PlannedBlock> out, Context ctx, BlockPos origin, int x, int y, int z, BlockState state) {
            MetaAssemblyEngine.put(out, ctx, origin, x, y, z, state);
        }

        @Override
        public BlockState pick(Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback) {
            return MetaAssemblyEngine.pick(ctx, op, overrideKey, semanticKey, salt, fallback);
        }

        @Override
        public int i(Object v, int def) {
            return MetaAssemblyEngine.i(v, def);
        }

        @Override
        public double d(Object v, double def) {
            return MetaAssemblyEngine.d(v, def);
        }

        @Override
        public double clamp(double v, double min, double max) {
            return MetaAssemblyEngine.clamp(v, min, max);
        }

        @Override
        public boolean bool(Object v, boolean def) {
            return MetaAssemblyEngine.bool(v, def);
        }

        @Override
        public String str(Object v, String def) {
            return MetaAssemblyEngine.str(v, def);
        }

        @Override
        public int clamp(int v, int min, int max) {
            return MetaAssemblyEngine.clamp(v, min, max);
        }
    };

    private static final AssemblyRouteOps.Adapter ROUTE_OPS_ADAPTER = new AssemblyRouteOps.Adapter() {
        @Override
        public BlockState pick(Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback) {
            return MetaAssemblyEngine.pick(ctx, op, overrideKey, semanticKey, salt, fallback);
        }

        @Override
        public int i(Object v, int def) {
            return MetaAssemblyEngine.i(v, def);
        }

        @Override
        public boolean bool(Object v, boolean def) {
            return MetaAssemblyEngine.bool(v, def);
        }

        @Override
        public int clamp(int v, int min, int max) {
            return MetaAssemblyEngine.clamp(v, min, max);
        }
    };

    private static final AssemblyInteriorOps.Adapter INTERIOR_OPS_ADAPTER = new AssemblyInteriorOps.Adapter() {
        @Override
        public BlockState pick(Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback) {
            return MetaAssemblyEngine.pick(ctx, op, overrideKey, semanticKey, salt, fallback);
        }

        @Override
        public int i(Object v, int def) {
            return MetaAssemblyEngine.i(v, def);
        }

        @Override
        public int clamp(int v, int min, int max) {
            return MetaAssemblyEngine.clamp(v, min, max);
        }
    };

    private static final AssemblyCirculationOps.Adapter CIRCULATION_OPS_ADAPTER = new AssemblyCirculationOps.Adapter() {
        @Override
        public void put(List<PlannedBlock> out, Context ctx, BlockPos origin, int x, int y, int z, BlockState state) {
            MetaAssemblyEngine.put(out, ctx, origin, x, y, z, state);
        }

        @Override
        public BlockState pick(Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback) {
            return MetaAssemblyEngine.pick(ctx, op, overrideKey, semanticKey, salt, fallback);
        }

        @Override
        public int i(Object v, int def) {
            return MetaAssemblyEngine.i(v, def);
        }

        @Override
        public boolean bool(Object v, boolean def) {
            return MetaAssemblyEngine.bool(v, def);
        }

        @Override
        public int clamp(int v, int min, int max) {
            return MetaAssemblyEngine.clamp(v, min, max);
        }
    };

    private static final AssemblyStructuralOps.Adapter STRUCTURAL_OPS_ADAPTER = new AssemblyStructuralOps.Adapter() {
        @Override
        public void placePrism(List<PlannedBlock> out, Context ctx, BlockPos origin, int cx, int cy, int cz, int thickness, int h, BlockState state) {
            MetaAssemblyEngine.placePrism(out, ctx, origin, cx, cy, cz, thickness, h, state);
        }

        @Override
        public void placeBeamLine(List<PlannedBlock> out, Context ctx, BlockPos origin, int x0, int y0, int z0, int x1, int y1, int z1, int thickness, int beamH, BlockState state) {
            MetaAssemblyEngine.placeBeamLine(out, ctx, origin, x0, y0, z0, x1, y1, z1, thickness, beamH, state);
        }

        @Override
        public BlockState pick(Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback) {
            return MetaAssemblyEngine.pick(ctx, op, overrideKey, semanticKey, salt, fallback);
        }

        @Override
        public int i(Object v, int def) {
            return MetaAssemblyEngine.i(v, def);
        }

        @Override
        public String str(Object v, String def) {
            return MetaAssemblyEngine.str(v, def);
        }

        @Override
        public int clamp(int v, int min, int max) {
            return MetaAssemblyEngine.clamp(v, min, max);
        }
    };

    public List<PlannedBlock> execute(AssemblySpec spec, Context ctx) {
        List<PlannedBlock> out = new ArrayList<>();
        if (spec == null || ctx == null || ctx.world == null || ctx.origin == null) return out;
        Deque<BlockPos> originStack = new ArrayDeque<>();
        BlockPos curOrigin = ctx.origin;
        for (Map<String, Object> op : spec.ops) {
            curOrigin = applyOp(out, ctx, originStack, curOrigin, op);
        }
        return out;
    }

    private BlockPos applyOp(List<PlannedBlock> out, Context ctx, Deque<BlockPos> originStack, BlockPos curOrigin, Map<String, Object> op) {
        if (op == null || op.isEmpty()) return curOrigin;
        String name = str(op.get("op"), "").toUpperCase(Locale.ROOT);
        if (name.isBlank()) return curOrigin;

        switch (name) {
            case "PUSH_ORIGIN" -> {
                int dx = i(op.get("dx"), 0);
                int dy = i(op.get("dy"), 0);
                int dz = i(op.get("dz"), 0);
                originStack.push(curOrigin);
                return curOrigin.add(dx, dy, dz);
            }
            case "POP_ORIGIN" -> {
                if (!originStack.isEmpty()) return originStack.pop();
                return curOrigin;
            }
            case "CLEAR_BOX" -> {
                int x0 = i(op.get("x0"), 0), y0 = i(op.get("y0"), 0), z0 = i(op.get("z0"), 0);
                int x1 = i(op.get("x1"), 0), y1 = i(op.get("y1"), 0), z1 = i(op.get("z1"), 0);
                fillBox(out, ctx, curOrigin, x0, y0, z0, x1, y1, z1, Blocks.AIR.getDefaultState());
            }
            case "ANCHOR_FOOTPRINT" -> {
                // Deep foundation / tower anchoring (P0):
                // For each (x,z) in a footprint rectangle, fill downward from yBase-1 until we hit solid ground,
                // or maxDepth is exhausted. Uses world query; does NOT overwrite solid ground by default.
                //
                // Required:
                // - x0,x1,z0,z1: local footprint bounds (inclusive)
                // Optional:
                // - yBase: base Y of structure (default 0)
                // - maxDepth: max downward fill depth (default 32)
                // - material: semantic FOUNDATION (default)
                // - stopOnSolid: stop when encountering a solid block (default true)
                // - allowWaterEdit/allowLavaEdit: whether to replace fluids while filling (default false)
                int x0 = i(op.get("x0"), 0), x1 = i(op.get("x1"), 0);
                int z0 = i(op.get("z0"), 0), z1 = i(op.get("z1"), 0);
                if (x0 > x1) { int t = x0; x0 = x1; x1 = t; }
                if (z0 > z1) { int t = z0; z0 = z1; z1 = t; }

                int yBase = i(op.get("yBase"), i(op.get("y"), 0));
                int maxDepth = clamp(i(op.get("maxDepth"), i(op.get("anchorDepth"), 32)), 0, 512);
                boolean stopOnSolid = bool(op.get("stopOnSolid"), true);
                boolean allowWaterEdit = bool(op.get("allowWaterEdit"), false);
                boolean allowLavaEdit = bool(op.get("allowLavaEdit"), false);

                BlockState mat = pick(ctx, op, "material", "FOUNDATION", 0xA57440L, Blocks.STONE_BRICKS.getDefaultState());

                int minY = Math.max(ctx.world.getBottomY(), yBase - maxDepth);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int y = yBase - 1; y >= minY; y--) {
                            BlockPos wp = PlacementUtil.local(curOrigin, ctx.entranceFacing, x, y, z);
                            BlockState cur = ctx.world.getBlockState(wp);
                            boolean hasFluid = !cur.getFluidState().isEmpty();
                            boolean solid = cur.isSolidBlock(ctx.world, wp) && !hasFluid;
                            if (solid && stopOnSolid) break;
                            if (hasFluid && !(allowWaterEdit || allowLavaEdit)) break;
                            // fill through air / allowed fluids
                            put(out, ctx, curOrigin, x, y, z, mat);
                        }
                    }
                }
            }
            case "ANCHORAGE" -> {
                // Main cable anchorage block (P0):
                // A heavy solid block plus optional deep foundation under its footprint.
                //
                // Optional:
                // - w,d,h: dimensions (defaults 12/10/8)
                // - yBase: bottom Y (default 0)
                // - solid/material: semantic FOUNDATION
                // - carve: if true, clears the block volume first (default false)
                // - maxDepth: deep foundation depth (default 24)
                // - allowWaterEdit/allowLavaEdit: allow filling fluids (default false)
                // - topBevel: stepped chamfer on top (default 0)
                // - guardWallHeight: add parapet on top perimeter (default 0)
                // - guardWallInset: inset of parapet from outer edge (default 0)
                // - guardWallCrenels: alternating crenels on top row (default false)
                // - guardWallMaterial/guardWall: parapet block (default stone_brick_wall)
                // - holes/cableHoles: list of cable holes [{face,y,x|z,r,len}] carved as square tunnels (air)
                int w = clamp(i(op.get("w"), i(op.get("width"), 12)), 3, 129);
                int d = clamp(i(op.get("d"), i(op.get("depth"), 10)), 3, 129);
                int h = clamp(i(op.get("h"), i(op.get("height"), 8)), 2, 255);
                int yBase = i(op.get("yBase"), i(op.get("y"), 0));
                int maxDepth = clamp(i(op.get("maxDepth"), i(op.get("anchorDepth"), 24)), 0, 512);
                boolean carve = bool(op.get("carve"), false);
                boolean allowWaterEdit = bool(op.get("allowWaterEdit"), false);
                boolean allowLavaEdit = bool(op.get("allowLavaEdit"), false);

                BlockState solid = pick(ctx, op, "solid", "FOUNDATION", 0xA57441L,
                        pick(ctx, op, "material", "FOUNDATION", 0xA57442L, Blocks.STONE_BRICKS.getDefaultState()));

                int halfW = w / 2;
                int halfD = d / 2;
                int x0 = -halfW;
                int z0 = -halfD;
                int y1 = yBase + h;

                if (carve) {
                    fillBox(out, ctx, curOrigin, x0, yBase, z0, halfW, y1, halfD, Blocks.AIR.getDefaultState());
                }
                fillBox(out, ctx, curOrigin, x0, yBase, z0, halfW, y1, halfD, solid);

                // Top bevel (stepped chamfer)
                int topBevel = clamp(i(op.get("topBevel"), i(op.get("top_bevel"), i(op.get("bevel"), 0))), 0, 32);
                for (int k = 0; k < topBevel; k++) {
                    int yy = y1 - k;
                    int inset = k + 1;
                    for (int x = x0; x <= halfW; x++) {
                        for (int z = z0; z <= halfD; z++) {
                            boolean keep = (x >= x0 + inset && x <= halfW - inset && z >= z0 + inset && z <= halfD - inset);
                            if (!keep) put(out, ctx, curOrigin, x, yy, z, Blocks.AIR.getDefaultState());
                        }
                    }
                }

                // Cable holes (air tunnels)
                Object holesObj = op.get("holes");
                if (holesObj == null) holesObj = op.get("cableHoles");
                if (holesObj instanceof List<?> holes) {
                    for (Object ho : holes) {
                        if (!(ho instanceof Map<?, ?> hm)) continue;
                        String face = str(hm.get("face"), "").trim().toUpperCase(Locale.ROOT);
                        if (face.isBlank()) continue;
                        int hy = i(hm.get("y"), yBase + (h / 2));
                        int hx = i(hm.get("x"), 0);
                        int hz = i(hm.get("z"), 0);
                        int r = clamp(i(hm.get("r"), i(hm.get("radius"), 1)), 1, 8);
                        int len = clamp(i(hm.get("len"), i(hm.get("length"), Math.max(4, Math.min(w, d) / 2))), 1, 128);

                        if (face.equals("WEST") || face.equals("EAST")) {
                            int sx = face.equals("WEST") ? x0 : halfW;
                            int step = face.equals("WEST") ? 1 : -1;
                            for (int t = 0; t <= len; t++) {
                                int x = sx + step * t;
                                if (x < x0 || x > halfW) break;
                                for (int yy = hy - r; yy <= hy + r; yy++) {
                                    if (yy < yBase || yy > y1) continue;
                                    for (int zz = hz - r; zz <= hz + r; zz++) {
                                        if (zz < z0 || zz > halfD) continue;
                                        put(out, ctx, curOrigin, x, yy, zz, Blocks.AIR.getDefaultState());
                                    }
                                }
                            }
                        } else if (face.equals("NORTH") || face.equals("SOUTH")) {
                            int sz = face.equals("NORTH") ? z0 : halfD;
                            int step = face.equals("NORTH") ? 1 : -1;
                            for (int t = 0; t <= len; t++) {
                                int z = sz + step * t;
                                if (z < z0 || z > halfD) break;
                                for (int yy = hy - r; yy <= hy + r; yy++) {
                                    if (yy < yBase || yy > y1) continue;
                                    for (int xx = hx - r; xx <= hx + r; xx++) {
                                        if (xx < x0 || xx > halfW) continue;
                                        put(out, ctx, curOrigin, xx, yy, z, Blocks.AIR.getDefaultState());
                                    }
                                }
                            }
                        }
                    }
                }

                // Guard wall / parapet
                int guardH = clamp(i(op.get("guardWallHeight"), i(op.get("guard_wall_height"), i(op.get("parapetHeight"), 0))), 0, 16);
                int guardInset = clamp(i(op.get("guardWallInset"), i(op.get("guard_wall_inset"), 0)), 0, 32);
                boolean guardCrenels = bool(op.get("guardWallCrenels"), bool(op.get("crenels"), false));
                BlockState guardWall = pick(ctx, op, "guardWallMaterial", "WALL_DETAIL", 0xA57443L,
                        pick(ctx, op, "guardWall", "WALL_DETAIL", 0xA57444L, Blocks.STONE_BRICK_WALL.getDefaultState()));
                if (guardH > 0) {
                    int gx0 = x0 + guardInset, gx1 = halfW - guardInset;
                    int gz0 = z0 + guardInset, gz1 = halfD - guardInset;
                    if (gx0 <= gx1 && gz0 <= gz1) {
                        for (int yy = 1; yy <= guardH; yy++) {
                            int y = y1 + yy;
                            boolean topRow = (yy == guardH);
                            for (int x = gx0; x <= gx1; x++) {
                                if (!(guardCrenels && topRow && ((x + gz0) & 1) == 1)) put(out, ctx, curOrigin, x, y, gz0, guardWall);
                                if (!(guardCrenels && topRow && ((x + gz1) & 1) == 1)) put(out, ctx, curOrigin, x, y, gz1, guardWall);
                            }
                            for (int z = gz0; z <= gz1; z++) {
                                if (!(guardCrenels && topRow && ((gx0 + z) & 1) == 1)) put(out, ctx, curOrigin, gx0, y, z, guardWall);
                                if (!(guardCrenels && topRow && ((gx1 + z) & 1) == 1)) put(out, ctx, curOrigin, gx1, y, z, guardWall);
                            }
                        }
                    }
                }

                if (maxDepth > 0) {
                    int minY = Math.max(ctx.world.getBottomY(), yBase - maxDepth);
                    for (int x = x0; x <= halfW; x++) {
                        for (int z = z0; z <= halfD; z++) {
                            for (int y = yBase - 1; y >= minY; y--) {
                                BlockPos wp = PlacementUtil.local(curOrigin, ctx.entranceFacing, x, y, z);
                                BlockState cur = ctx.world.getBlockState(wp);
                                boolean hasFluid = !cur.getFluidState().isEmpty();
                                boolean solidGround = cur.isSolidBlock(ctx.world, wp) && !hasFluid;
                                if (solidGround) break;
                                if (hasFluid && !(allowWaterEdit || allowLavaEdit)) break;
                                put(out, ctx, curOrigin, x, y, z, solid);
                            }
                        }
                    }
                }
            }
            case "SHELL_BOX" -> {
                // box shell with semantic materials
                int w = clamp(i(op.get("w"), 15), 5, 129);
                int d = clamp(i(op.get("d"), 15), 5, 129);
                int h = clamp(i(op.get("h"), 18), 6, 255);
                int floorStep = clamp(i(op.get("floorStep"), 4), 3, 8);
                // Forma-Gene integration: twist support (twistTurns: number of full rotations, e.g., 0.25 = 90°, 1.0 = 360°)
                double twistTurns = clamp(d(op.get("twistTurns"), d(op.get("twist_turns"), 0.0)), -2.0, 2.0);
                double twistPhase = clamp(d(op.get("twistPhase"), d(op.get("twist_phase"), 0.0)), 0.0, 1.0);

                BlockState wall = pick(ctx, op, "wall", "WALL_BASE", 0xA55001L, Blocks.STONE_BRICKS.getDefaultState());
                BlockState glass = pick(ctx, op, "window", "WINDOW", 0xA55002L, Blocks.GLASS_PANE.getDefaultState());
                BlockState floor = pick(ctx, op, "floor", "FLOORING", 0xA55003L, Blocks.SMOOTH_STONE.getDefaultState());
                BlockState roof = pick(ctx, op, "roof", "FLOOR_SLAB", 0xA55004L, Blocks.SMOOTH_STONE_SLAB.getDefaultState());

                // local coords: centered around origin (like OfficeBlock)
                int halfW = w / 2;
                int halfD = d / 2;

                boolean hasTwist = Math.abs(twistTurns) > 0.001;

                // shell walls
                for (int yy = 0; yy <= h; yy++) {
                    boolean windowBand = (yy % 4 == 2) && yy <= h - 2;
                    double t = h > 0 ? (double) yy / h : 0.0; // 0..1
                    double angle = (twistTurns * Math.PI * 2.0) * t + (twistPhase * Math.PI * 2.0);
                    double cosA = Math.cos(angle);
                    double sinA = Math.sin(angle);

                    for (int x = -halfW; x <= halfW; x++) {
                        for (int z = -halfD; z <= halfD; z++) {
                            boolean edge = (Math.abs(x) == halfW) || (Math.abs(z) == halfD);
                            if (!edge) continue;

                            int finalX = x;
                            int finalZ = z;
                            if (hasTwist) {
                                // Rotate coordinates around center (0,0)
                                double rx = x * cosA - z * sinA;
                                double rz = x * sinA + z * cosA;
                                finalX = (int) Math.round(rx);
                                finalZ = (int) Math.round(rz);
                            }

                            BlockState s = wall;
                            if (windowBand && (Math.abs(x) != halfW || Math.abs(z) != halfD)) s = glass;
                            put(out, ctx, curOrigin, finalX, yy, finalZ, s);
                        }
                    }
                }

                // floors (with twist)
                for (int yy = 0; yy <= h; yy += floorStep) {
                    double t = h > 0 ? (double) yy / h : 0.0;
                    double angle = (twistTurns * Math.PI * 2.0) * t + (twistPhase * Math.PI * 2.0);
                    double cosA = Math.cos(angle);
                    double sinA = Math.sin(angle);

                    for (int x = -halfW + 1; x <= halfW - 1; x++) {
                        for (int z = -halfD + 1; z <= halfD - 1; z++) {
                            int finalX = x;
                            int finalZ = z;
                            if (hasTwist) {
                                double rx = x * cosA - z * sinA;
                                double rz = x * sinA + z * cosA;
                                finalX = (int) Math.round(rx);
                                finalZ = (int) Math.round(rz);
                            }
                            put(out, ctx, curOrigin, finalX, yy, finalZ, floor);
                        }
                    }
                }

                // roof cap (with twist)
                if (h > 0) {
                    double angle = (twistTurns * Math.PI * 2.0) + (twistPhase * Math.PI * 2.0);
                    double cosA = Math.cos(angle);
                    double sinA = Math.sin(angle);
                    for (int x = -halfW; x <= halfW; x++) {
                        for (int z = -halfD; z <= halfD; z++) {
                            int finalX = x;
                            int finalZ = z;
                            if (hasTwist) {
                                double rx = x * cosA - z * sinA;
                                double rz = x * sinA + z * cosA;
                                finalX = (int) Math.round(rx);
                                finalZ = (int) Math.round(rz);
                            }
                            put(out, ctx, curOrigin, finalX, h + 1, finalZ, roof);
                        }
                    }
                }

                // hollow interior (approximate with twist - use bounding box)
                if (hasTwist) {
                    // For twisted boxes, clear a larger bounding box to ensure interior is hollow
                    int maxRadius = (int) Math.ceil(Math.sqrt(halfW * halfW + halfD * halfD));
                    fillBox(out, ctx, curOrigin, -maxRadius, 1, -maxRadius, maxRadius, h, maxRadius, Blocks.AIR.getDefaultState());
                } else {
                    fillBox(out, ctx, curOrigin, -halfW + 1, 1, -halfD + 1, halfW - 1, h, halfD - 1, Blocks.AIR.getDefaultState());
                }
            }
            case "CYLINDER" -> {
                // Cylinder (filled or hollow shell) in local coords centered at current origin.
                int r = clamp(i(op.get("r"), i(op.get("radius"), 6)), 2, 128);
                int h = clamp(i(op.get("h"), i(op.get("height"), 18)), 3, 255);
                boolean hollow = bool(op.get("hollow"), false);
                int thickness = clamp(i(op.get("thickness"), 1), 1, Math.max(1, r));

                BlockState solid = pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0xA55201L, Blocks.STONE_BRICKS.getDefaultState());
                BlockState shell = pick(ctx, op, "wall", "WALL_BASE", 0xA55202L, solid);

                int r2 = r * r;
                int innerR = Math.max(0, r - thickness);
                int inner2 = innerR * innerR;
                for (int x = -r; x <= r; x++) {
                    for (int z = -r; z <= r; z++) {
                        int d2 = x * x + z * z;
                        if (d2 > r2) continue;
                        for (int y = 0; y < h; y++) {
                            if (!hollow) {
                                put(out, ctx, curOrigin, x, y, z, solid);
                            } else {
                                // shell: place wall on outer band, carve inside
                                if (d2 >= inner2) put(out, ctx, curOrigin, x, y, z, shell);
                                else put(out, ctx, curOrigin, x, y, z, Blocks.AIR.getDefaultState());
                            }
                        }
                    }
                }
            }
            case "CONNECTOR_LINE" -> {
                // Simple 3D connector between two points: places a beam prism along the sampled line.
                // Expected keys:
                // - from/to maps {x,y,z} or x0,y0,z0,x1,y1,z1
                int x0, y0, z0, x1, y1, z1;
                Object from = op.get("from");
                Object to = op.get("to");
                if (from instanceof Map<?, ?> fm) {
                    x0 = i(fm.get("x"), 0); y0 = i(fm.get("y"), 0); z0 = i(fm.get("z"), 0);
                } else {
                    x0 = i(op.get("x0"), 0); y0 = i(op.get("y0"), 0); z0 = i(op.get("z0"), 0);
                }
                if (to instanceof Map<?, ?> tm) {
                    x1 = i(tm.get("x"), 0); y1 = i(tm.get("y"), 0); z1 = i(tm.get("z"), 0);
                } else {
                    x1 = i(op.get("x1"), 0); y1 = i(op.get("y1"), 0); z1 = i(op.get("z1"), 0);
                }

                int thickness = clamp(i(op.get("thickness"), 1), 1, 9);
                int beamH = clamp(i(op.get("h"), i(op.get("height"), 1)), 1, 9);

                BlockState mat = pick(ctx, op, "material", "STRUCTURAL_BEAM", 0xA55301L, Blocks.SPRUCE_LOG.getDefaultState());

                int dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
                int steps = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
                if (steps <= 0) {
                    placePrism(out, ctx, curOrigin, x0, y0, z0, thickness, beamH, mat);
                    break;
                }
                for (int i = 0; i <= steps; i++) {
                    double t = i / (double) steps;
                    int x = (int) Math.round(x0 + dx * t);
                    int y = (int) Math.round(y0 + dy * t);
                    int z = (int) Math.round(z0 + dz * t);
                    placePrism(out, ctx, curOrigin, x, y, z, thickness, beamH, mat);
                }
            }
            case "TRUSS_2D" -> AssemblyStructuralOps.applyTruss2D(out, ctx, curOrigin, op, STRUCTURAL_OPS_ADAPTER);
            case "ARCH_RIB" -> AssemblyStructuralOps.applyArchRib(out, ctx, curOrigin, op, STRUCTURAL_OPS_ADAPTER);
            case "BUTTRESS" -> AssemblyStructuralOps.applyButtress(out, ctx, curOrigin, op, STRUCTURAL_OPS_ADAPTER);
            case "TENSION_CABLE" -> AssemblyStructuralOps.applyTensionCable(out, ctx, curOrigin, op, STRUCTURAL_OPS_ADAPTER);
            case "FRAME_GRID_3D" -> AssemblyStructuralOps.applyFrameGrid3D(out, ctx, curOrigin, op, STRUCTURAL_OPS_ADAPTER);
            case "STAIR_SYSTEM" -> AssemblyCirculationOps.applyStairSystem(out, ctx, curOrigin, op, CIRCULATION_OPS_ADAPTER);
            case "BEZIER_SURFACE" -> AssemblySurfaceOps.applyBezierSurface(out, ctx, curOrigin, op, SURFACE_OPS_ADAPTER);
            case "BEZIER_SURFACE_SET" -> AssemblySurfaceOps.applyBezierSurfaceSet(out, ctx, curOrigin, op, SURFACE_OPS_ADAPTER);
            case "SURFACE_OFFSET" -> AssemblySurfaceOps.applySurfaceOffset(out, ctx, curOrigin, op, SURFACE_OPS_ADAPTER);
            case "IMPLICIT_FIELD" -> AssemblyIsoSurfaceOps.applyImplicitField(out, ctx, curOrigin, op, ISO_SURFACE_OPS_ADAPTER);
            case "MARCHING_CUBES" -> AssemblyIsoSurfaceOps.applyMarchingCubes(out, ctx, curOrigin, op, ISO_SURFACE_OPS_ADAPTER);
            case "REVOLVE_SURFACE" -> AssemblySurfaceOps.applyRevolveSurface(out, ctx, curOrigin, op, SURFACE_OPS_ADAPTER);
            case "LOFT_SURFACE" -> AssemblySurfaceOps.applyLoftSurface(out, ctx, curOrigin, op, SURFACE_OPS_ADAPTER);
            case "PATH_ROUTE" -> AssemblyRouteOps.applyPathRoute(out, ctx, curOrigin, op, ROUTE_OPS_ADAPTER);
            case "WALL_ROUTE" -> AssemblyRouteOps.applyWallRoute(out, ctx, curOrigin, op, ROUTE_OPS_ADAPTER);
            case "BRIDGE_ROUTE" -> AssemblyRouteOps.applyBridgeRoute(out, ctx, curOrigin, op, ROUTE_OPS_ADAPTER);
            case "EXTRUDE_POLYGON" -> AssemblySolidOps.applyExtrudePolygon(out, ctx, curOrigin, op, SOLID_OPS_ADAPTER);
            case "ROOF_COVER" -> AssemblySolidOps.applyRoofCover(out, ctx, curOrigin, op, SOLID_OPS_ADAPTER);
            case "BSP_FLOOR_PLAN" -> AssemblyInteriorOps.applyBspFloorPlan(out, ctx, curOrigin, op, INTERIOR_OPS_ADAPTER);
            case "SPLINE_SWEEP", "SPLINE_TUBE" -> AssemblySurfaceOps.applySplineSweep(out, ctx, curOrigin, op, SURFACE_OPS_ADAPTER);
            case "SURFACE_PATTERN" -> AssemblyFacadeOps.applySurfacePattern(out, ctx, curOrigin, op, FACADE_OPS_ADAPTER);
            case "FACADE_GRID" -> AssemblyFacadeOps.applyFacadeGrid(out, ctx, curOrigin, op, FACADE_OPS_ADAPTER);
            case "SURFACE_BANDS" -> AssemblyFacadeOps.applySurfaceBands(out, ctx, curOrigin, op, FACADE_OPS_ADAPTER);
            case "OPENINGS" -> AssemblyFacadeOps.applyOpenings(out, ctx, curOrigin, op, FACADE_OPS_ADAPTER);
            default -> {
                // ignore unknown ops for forward compatibility
            }
        }
        return curOrigin;
    }

    private static void put(List<PlannedBlock> out, Context ctx, BlockPos origin, int x, int y, int z, BlockState s) {
        if (s == null) return;
        BlockPos p = PlacementUtil.local(origin, ctx.entranceFacing, x, y, z);
        out.add(new PlannedBlock(p, PlacementUtil.rotateState(s, ctx.entranceFacing)));
    }

    private static void fillBox(List<PlannedBlock> out, Context ctx, BlockPos origin, int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        int ax0 = Math.min(x0, x1), ax1 = Math.max(x0, x1);
        int ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
        int az0 = Math.min(z0, z1), az1 = Math.max(z0, z1);
        for (int x = ax0; x <= ax1; x++) for (int y = ay0; y <= ay1; y++) for (int z = az0; z <= az1; z++) put(out, ctx, origin, x, y, z, s);
    }

    private static BlockState pick(Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback) {
        // allow explicit override: e.g. { "wall": "minecraft:stone_bricks" }
        Object ov = op.get(overrideKey);
        if (ov != null) {
            BlockState parsed = parseBlockId(ctx.world, String.valueOf(ov).trim());
            if (parsed != null) return parsed;
        }
        if (ctx.paletteId != null && !ctx.paletteId.isBlank()) {
            return PaletteResolver.pick(ctx.world, ctx.paletteId, semanticKey, ctx.origin, salt, fallback);
        }
        return fallback;
    }

    private static BlockState parseBlockId(ServerWorld world, String id) {
        if (id == null || id.isBlank()) return null;
        try {
            var ident = net.minecraft.util.Identifier.tryParse(id);
            if (ident == null) return null;
            return net.minecraft.registry.Registries.BLOCK.get(ident).getDefaultState();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Compute anchor position for asset placement based on placement rule.
     */
    private static BlockPos computeAssetAnchor(BlockPos origin, Direction entranceFacing, String face, String placement, 
                                               int x0, int x1, int y0, int y1, int z0, int z1) {
        // For now, use a simple placement: center of the face at middle Y
        // TODO: Implement more sophisticated placement rules (WINDOW_FRAME, COLUMN_TOP, etc.)
        int cx = (x0 + x1) / 2;
        int cy = (y0 + y1) / 2;
        int cz = (z0 + z1) / 2;
        
        // Adjust based on face (place on the surface)
        return switch (face) {
            case "NORTH" -> PlacementUtil.local(origin, entranceFacing, cx, cy, z0);
            case "SOUTH" -> PlacementUtil.local(origin, entranceFacing, cx, cy, z1);
            case "EAST" -> PlacementUtil.local(origin, entranceFacing, x1, cy, cz);
            case "WEST" -> PlacementUtil.local(origin, entranceFacing, x0, cy, cz);
            default -> PlacementUtil.local(origin, entranceFacing, cx, cy, cz);
        };
    }
    
    /**
     * Parse face direction and convert to world direction considering entrance facing.
     * Returns the direction perpendicular to the face (pointing outward).
     */
    private static Direction parseFaceDirection(String face, Direction entranceFacing) {
        Direction localDir = switch (face) {
            case "SOUTH" -> Direction.SOUTH;
            case "EAST" -> Direction.EAST;
            case "WEST" -> Direction.WEST;
            default -> Direction.NORTH;
        };
        // Rotate local direction to world direction (entranceFacing is the rotation)
        if (entranceFacing == null) entranceFacing = Direction.SOUTH;
        return switch (entranceFacing) {
            case NORTH -> switch (localDir) {
                case NORTH -> Direction.SOUTH;
                case SOUTH -> Direction.NORTH;
                case EAST -> Direction.WEST;
                case WEST -> Direction.EAST;
                default -> localDir;
            };
            case EAST -> switch (localDir) {
                case NORTH -> Direction.EAST;
                case SOUTH -> Direction.WEST;
                case EAST -> Direction.NORTH;
                case WEST -> Direction.SOUTH;
                default -> localDir;
            };
            case WEST -> switch (localDir) {
                case NORTH -> Direction.WEST;
                case SOUTH -> Direction.EAST;
                case EAST -> Direction.SOUTH;
                case WEST -> Direction.NORTH;
                default -> localDir;
            };
            default -> localDir; // SOUTH (default)
        };
    }
    
    private static void placePrism(List<PlannedBlock> out, Context ctx, BlockPos origin, int cx, int cy, int cz, int thickness, int h, BlockState s) {
        AssemblyVoxelBridgeOps.placePrism(
                (x, y, z, state) -> put(out, ctx, origin, x, y, z, state),
                cx, cy, cz, thickness, h, s
        );
    }

    private static void placeBeamLine(List<PlannedBlock> out,
                                      Context ctx,
                                      BlockPos origin,
                                      int x0, int y0, int z0,
                                      int x1, int y1, int z1,
                                      int thickness,
                                      int beamH,
                                      BlockState s) {
        AssemblyVoxelBridgeOps.placeBeamLine(
                (x, y, z, state) -> put(out, ctx, origin, x, y, z, state),
                x0, y0, z0, x1, y1, z1, thickness, beamH, s
        );
    }

    private static void connectSurfaceGrid(List<PlannedBlock> out, Context ctx, BlockPos origin,
                                           int[][][] grid, int uN, int vN, int thick, BlockState mat) {
        AssemblyVoxelBridgeOps.connectSurfaceGrid(
                (x, y, z, state) -> put(out, ctx, origin, x, y, z, state),
                grid, uN, vN, thick, mat
        );
    }

    // Ray casting point-in-polygon test (works for simple polygons; points on edge treated as inside-ish)
    private static int i(Object v, int def) {
        return AssemblyValueParser.i(v, def);
    }

    private static double d(Object v, double def) {
        return AssemblyValueParser.d(v, def);
    }

    private static double clamp(double v, double min, double max) {
        return AssemblyValueParser.clamp(v, min, max);
    }

    private static boolean bool(Object v, boolean def) {
        return AssemblyValueParser.bool(v, def);
    }

    private static String str(Object v, String def) {
        return AssemblyValueParser.str(v, def);
    }

    private static int clamp(int v, int min, int max) {
        return AssemblyValueParser.clamp(v, min, max);
    }
}
