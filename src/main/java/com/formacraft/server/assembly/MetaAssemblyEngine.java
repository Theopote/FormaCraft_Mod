package com.formacraft.server.assembly;

import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.generator.BridgeGenerator;
import com.formacraft.server.generator.path.PathGenerator;
import com.formacraft.common.model.path.PathSpec;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.server.skeleton.linear.LinearWallInterpreter;
import com.formacraft.common.skeleton.linear.LinearPathPlan;
import com.formacraft.server.interior.BspFloorPlanGenerator;
import com.formacraft.server.interior.FloorPlanConfig;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
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

    private static final int[][] DIR6 = new int[][]{
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
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
            case "TRUSS_2D" -> {
                // 2D truss skeleton (in XZ with height in Y).
                //
                // Required:
                // - from/to: {x,y,z} local points
                // Optional:
                // - height: truss height (default 6)
                // - module: spacing in steps along the centerline (default 4)
                // - pattern: WARREN (default) / PRATT / HOWE (P0: WARREN best-effort)
                // - thickness: beam thickness (default 1)
                // - chord: material for top/bottom chords (semantic STRUCTURAL_BEAM)
                // - web: material for diagonals/verticals (semantic STRUCTURAL_BEAM)
                // - joint: joint material (semantic STRUCTURAL_BEAM)
                int[] p0 = AssemblyRasterOps.parsePoint(op.get("from"));
                int[] p1 = AssemblyRasterOps.parsePoint(op.get("to"));
                int baseY = Math.min(p0[1], p1[1]);
                int height = clamp(i(op.get("height"), i(op.get("h"), 6)), 1, 64);
                int topY = baseY + height;
                int module = clamp(i(op.get("module"), i(op.get("step"), 4)), 1, 64);
                String pattern = str(op.get("pattern"), "WARREN").trim().toUpperCase(Locale.ROOT);
                int thick = clamp(i(op.get("thickness"), 1), 1, 9);

                BlockState chord = pick(ctx, op, "chord", "STRUCTURAL_BEAM", 0xA57401L, Blocks.IRON_BARS.getDefaultState());
                BlockState web = pick(ctx, op, "web", "STRUCTURAL_BEAM", 0xA57402L, chord);
                BlockState joint = pick(ctx, op, "joint", "STRUCTURAL_BEAM", 0xA57403L, chord);

                // Work in local coordinates; rasterize XZ line at baseY
                BlockPos a = new BlockPos(p0[0], baseY, p0[2]);
                BlockPos b = new BlockPos(p1[0], baseY, p1[2]);
                List<BlockPos> line = AssemblyRasterOps.rasterizeLine2D(a, b, null, false, 0);
                if (line.size() < 2) break;

                // Place chords (bottom + top)
                for (BlockPos lp : line) {
                    placePrism(out, ctx, curOrigin, lp.getX(), baseY, lp.getZ(), thick, 1, chord);
                    placePrism(out, ctx, curOrigin, lp.getX(), topY, lp.getZ(), thick, 1, chord);
                }

                // Place joints + webs on module nodes
                int n = line.size();
                int lastNode = 0;
                boolean flip = false;
                for (int i = 0; i < n; i += module) {
                    int idx = Math.min(i, n - 1);
                    BlockPos p = line.get(idx);
                    // joints
                    placePrism(out, ctx, curOrigin, p.getX(), baseY, p.getZ(), thick, 1, joint);
                    placePrism(out, ctx, curOrigin, p.getX(), topY, p.getZ(), thick, 1, joint);

                    // vertical at node
                    placeBeamLine(out, ctx, curOrigin, p.getX(), baseY, p.getZ(), p.getX(), topY, p.getZ(), thick, 1, web);

                    // diagonal from previous node to current node (Warren-ish)
                    if (idx > 0) {
                        BlockPos prev = line.get(lastNode);
                        if (pattern.contains("PRATT") || pattern.contains("HOWE")) {
                            // P0: treat as WARREN (diagonals alternate)
                        }
                        if (!flip) {
                            // bottom(prev) -> top(cur)
                            placeBeamLine(out, ctx, curOrigin, prev.getX(), baseY, prev.getZ(), p.getX(), topY, p.getZ(), thick, 1, web);
                        } else {
                            // top(prev) -> bottom(cur)
                            placeBeamLine(out, ctx, curOrigin, prev.getX(), topY, prev.getZ(), p.getX(), baseY, p.getZ(), thick, 1, web);
                        }
                        flip = !flip;
                        lastNode = idx;
                    }
                }
            }
            case "ARCH_RIB" -> {
                // Curved arch rib in a vertical plane that contains the (from->to) direction and Y axis.
                //
                // Required:
                // - from/to: {x,y,z} local points
                // Optional:
                // - rise: arch rise (sagitta) above the straight chord (default auto)
                // - thickness: beam thickness (default 1)
                // - samples: number of samples along arch (default auto by distance)
                // - material: block/material semantic (default STRUCTURAL_BEAM)
                int[] p0 = AssemblyRasterOps.parsePoint(op.get("from"));
                int[] p1 = AssemblyRasterOps.parsePoint(op.get("to"));
                int thick = clamp(i(op.get("thickness"), 1), 1, 9);

                int dx = p1[0] - p0[0];
                int dz = p1[2] - p0[2];
                int dist2d = Math.max(Math.abs(dx), Math.abs(dz));
                int rise = i(op.get("rise"), i(op.get("sagitta"), -1));
                if (rise <= 0) rise = clamp(Math.max(2, dist2d / 6), 2, 48);

                int samples = i(op.get("samples"), i(op.get("steps"), -1));
                if (samples <= 0) samples = clamp(dist2d * 3, 12, 4096);

                BlockState mat = pick(ctx, op, "material", "STRUCTURAL_BEAM", 0xA57410L, Blocks.IRON_BARS.getDefaultState());

                int lastX = p0[0], lastY = p0[1], lastZ = p0[2];
                boolean first = true;
                for (int si = 0; si <= samples; si++) {
                    double t = si / (double) samples;
                    double x = p0[0] + (p1[0] - p0[0]) * t;
                    double yLine = p0[1] + (p1[1] - p0[1]) * t;
                    double z = p0[2] + (p1[2] - p0[2]) * t;
                    // Parabolic arch offset: 0 at ends, rise at mid
                    double y = yLine + (4.0 * rise * t * (1.0 - t));

                    int xi = (int) Math.round(x);
                    int yi = (int) Math.round(y);
                    int zi = (int) Math.round(z);

                    if (first) {
                        placePrism(out, ctx, curOrigin, xi, yi, zi, thick, 1, mat);
                        first = false;
                    } else {
                        placeBeamLine(out, ctx, curOrigin, lastX, lastY, lastZ, xi, yi, zi, thick, 1, mat);
                    }
                    lastX = xi; lastY = yi; lastZ = zi;
                }
            }
            case "BUTTRESS" -> {
                // Flying buttress (P0): an arch rib from wall attachment to an outer pier point,
                // plus an optional vertical pier-down support.
                //
                // Required:
                // - from/to: {x,y,z} local points
                // Optional:
                // - rise: arch rise above chord (default auto)
                // - thickness: beam thickness (default 1)
                // - samples: arch samples (default auto)
                // - pierDown: extend a vertical pier down from 'to' by N blocks (default 6)
                // - rib/pier/joint: materials (semantic STRUCTURAL_BEAM)
                int[] p0 = AssemblyRasterOps.parsePoint(op.get("from"));
                int[] p1 = AssemblyRasterOps.parsePoint(op.get("to"));

                int thick = clamp(i(op.get("thickness"), 1), 1, 9);
                int dx = p1[0] - p0[0];
                int dz = p1[2] - p0[2];
                int dist2d = Math.max(Math.abs(dx), Math.abs(dz));
                int rise = i(op.get("rise"), i(op.get("sagitta"), -1));
                if (rise <= 0) rise = clamp(Math.max(2, dist2d / 6), 2, 48);
                int samples = i(op.get("samples"), i(op.get("steps"), -1));
                if (samples <= 0) samples = clamp(dist2d * 3, 12, 4096);
                int pierDown = i(op.get("pierDown"), i(op.get("pier_down"), 6));
                pierDown = clamp(pierDown, 0, 256);

                BlockState rib = pick(ctx, op, "rib", "STRUCTURAL_BEAM", 0xA57420L, Blocks.IRON_BARS.getDefaultState());
                BlockState pier = pick(ctx, op, "pier", "STRUCTURAL_BEAM", 0xA57421L, rib);
                BlockState joint = pick(ctx, op, "joint", "STRUCTURAL_BEAM", 0xA57422L, rib);

                // joints at endpoints
                placePrism(out, ctx, curOrigin, p0[0], p0[1], p0[2], thick, 1, joint);
                placePrism(out, ctx, curOrigin, p1[0], p1[1], p1[2], thick, 1, joint);

                // main flying arch rib
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
                        placePrism(out, ctx, curOrigin, xi, yi, zi, thick, 1, rib);
                        first = false;
                    } else {
                        placeBeamLine(out, ctx, curOrigin, lastX, lastY, lastZ, xi, yi, zi, thick, 1, rib);
                    }
                    lastX = xi; lastY = yi; lastZ = zi;
                }

                // outer pier support (simple vertical)
                if (pierDown > 0) {
                    placeBeamLine(out, ctx, curOrigin, p1[0], p1[1], p1[2], p1[0], p1[1] - pierDown, p1[2], thick, 1, pier);
                }
            }
            case "TENSION_CABLE" -> {
                // Tension cable / hanger cable (P0): a sagging curve between endpoints.
                // We approximate a catenary with a parabola in world-space: y = yLine - sag * 4 t(1-t).
                //
                // Required:
                // - from/to: {x,y,z} local points
                // Optional:
                // - sag: positive number; larger means more droop (default auto)
                // - samples: points along curve (default auto)
                // - thickness: beam thickness (default 1)
                // - material: semantic STRUCTURAL_CABLE (fallback STRUCTURAL_BEAM)
                // - hangersEvery: place vertical hangers every N samples (default 0 = off)
                // - hangersToY: bottom Y of hangers (required if hangersEvery>0)
                // - hangersMaterial: semantic STRUCTURAL_CABLE (fallback to material)
                // - cableCount: number of parallel main cables (default 1)
                // - cableSpacing: spacing between parallel cables (default 3)
                // - cableAxis: AUTO/X/Z (offset direction in XZ; AUTO picks perpendicular-ish axis)
                int[] p0 = AssemblyRasterOps.parsePoint(op.get("from"));
                int[] p1 = AssemblyRasterOps.parsePoint(op.get("to"));

                int thick = clamp(i(op.get("thickness"), 1), 1, 5);
                int dx = p1[0] - p0[0];
                int dy = p1[1] - p0[1];
                int dz = p1[2] - p0[2];
                int dist = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));

                int sag = i(op.get("sag"), i(op.get("droop"), -1));
                if (sag <= 0) sag = clamp(Math.max(1, dist / 12), 1, 48);

                int samples = i(op.get("samples"), i(op.get("steps"), -1));
                if (samples <= 0) samples = clamp(dist * 4, 12, 8192);

                BlockState mat = pick(ctx, op, "material", "STRUCTURAL_CABLE", 0xA57430L,
                        pick(ctx, op, "material", "STRUCTURAL_BEAM", 0xA57431L, Blocks.IRON_BARS.getDefaultState()));

                int hangersEvery = i(op.get("hangersEvery"), i(op.get("hangerEvery"), 0));
                int hangersToY = i(op.get("hangersToY"), i(op.get("hangerToY"), Integer.MIN_VALUE));
                BlockState hangMat = pick(ctx, op, "hangersMaterial", "STRUCTURAL_CABLE", 0xA57432L, mat);
                boolean doHangers = hangersEvery > 0 && hangersToY != Integer.MIN_VALUE;

                int cableCount = clamp(i(op.get("cableCount"), i(op.get("count"), 1)), 1, 32);
                int cableSpacing = clamp(i(op.get("cableSpacing"), i(op.get("spacing"), 3)), 1, 64);
                String cableAxis = str(op.get("cableAxis"), "AUTO").trim().toUpperCase(Locale.ROOT);

                // Offset direction in XZ: choose axis perpendicular-ish to cable direction.
                // If cable is mostly along X, offset in Z; if mostly along Z, offset in X.
                boolean offsetX;
                if ("X".equals(cableAxis)) offsetX = true;
                else if ("Z".equals(cableAxis)) offsetX = false;
                else offsetX = Math.abs(dz) >= Math.abs(dx); // along Z => offset X, else offset Z

                for (int ci = 0; ci < cableCount; ci++) {
                    int center = (cableCount - 1);
                    // symmetric offsets: -k..+k
                    int off = (ci * 2 - center);
                    // keep even spacing when count is even by allowing half-step; here we scale then /2.
                    // ex: count=2 => off=-1,+1 => +/-spacing/2 in effect
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
                            placePrism(out, ctx, curOrigin, xi, yi, zi, thick, 1, mat);
                            first = false;
                        } else {
                            placeBeamLine(out, ctx, curOrigin, lastX, lastY, lastZ, xi, yi, zi, thick, 1, mat);
                        }

                        // vertical hangers (down to fixed Y)
                        if (doHangers && si % hangersEvery == 0) {
                            if (yi > hangersToY) {
                                placeBeamLine(out, ctx, curOrigin, xi, yi, zi, xi, hangersToY, zi, 1, 1, hangMat);
                            }
                        }
                        lastX = xi; lastY = yi; lastZ = zi;
                    }
                }
            }
            case "FRAME_GRID_3D" -> {
                // 3D frame/grid skeleton (space frame / exoskeleton).
                //
                // Required:
                // - x0,x1,y0,y1,z0,z1: local bounds (inclusive)
                // Optional:
                // - stepX/stepY/stepZ: grid spacing (default 4)
                // - thickness: beam thickness (default 1)
                // - mode: SURFACE (default) / ALL
                // - diagonal: NONE (default) / FACE / SPACE
                // - material: semantic STRUCTURAL_BEAM
                int x0 = i(op.get("x0"), 0), x1 = i(op.get("x1"), 0);
                int y0 = i(op.get("y0"), 0), y1 = i(op.get("y1"), 0);
                int z0 = i(op.get("z0"), 0), z1 = i(op.get("z1"), 0);
                if (x0 > x1) { int t = x0; x0 = x1; x1 = t; }
                if (y0 > y1) { int t = y0; y0 = y1; y1 = t; }
                if (z0 > z1) { int t = z0; z0 = z1; z1 = t; }

                int stepX = clamp(i(op.get("stepX"), i(op.get("sx"), i(op.get("step"), 4))), 1, 64);
                int stepY = clamp(i(op.get("stepY"), i(op.get("sy"), i(op.get("step"), 4))), 1, 64);
                int stepZ = clamp(i(op.get("stepZ"), i(op.get("sz"), i(op.get("step"), 4))), 1, 64);
                int thick = clamp(i(op.get("thickness"), 1), 1, 9);
                String mode = str(op.get("mode"), "SURFACE").trim().toUpperCase(Locale.ROOT);
                String diagonal = str(op.get("diagonal"), "NONE").trim().toUpperCase(Locale.ROOT);

                BlockState mat = pick(ctx, op, "material", "STRUCTURAL_BEAM", 0xA57460L, Blocks.IRON_BARS.getDefaultState());

                // Build snap lists for grid coordinates (always include bounds)
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

                // Draw axis-aligned beams along grid lines
                for (int yi : ys) {
                    for (int zi : zs) {
                        for (int xiIdx = 0; xiIdx + 1 < xs.size(); xiIdx++) {
                            int xa = xs.get(xiIdx), xb = xs.get(xiIdx + 1);
                            if (!all) {
                                boolean onSurface = (yi == y0 || yi == y1) || (zi == z0 || zi == z1);
                                if (!onSurface) continue;
                            }
                            placeBeamLine(out, ctx, curOrigin, xa, yi, zi, xb, yi, zi, thick, 1, mat);
                        }
                    }
                }
                for (int yi : ys) {
                    for (int xi : xs) {
                        for (int ziIdx = 0; ziIdx + 1 < zs.size(); ziIdx++) {
                            int za = zs.get(ziIdx), zb = zs.get(ziIdx + 1);
                            if (!all) {
                                boolean onSurface = (yi == y0 || yi == y1) || (xi == x0 || xi == x1);
                                if (!onSurface) continue;
                            }
                            placeBeamLine(out, ctx, curOrigin, xi, yi, za, xi, yi, zb, thick, 1, mat);
                        }
                    }
                }
                for (int zi : zs) {
                    for (int xi : xs) {
                        for (int yiIdx = 0; yiIdx + 1 < ys.size(); yiIdx++) {
                            int ya = ys.get(yiIdx), yb = ys.get(yiIdx + 1);
                            if (!all) {
                                boolean onSurface = (xi == x0 || xi == x1) || (zi == z0 || zi == z1);
                                if (!onSurface) continue;
                            }
                            placeBeamLine(out, ctx, curOrigin, xi, ya, zi, xi, yb, zi, thick, 1, mat);
                        }
                    }
                }

                // Diagonals
                if (!diagonal.contains("NONE")) {
                    boolean faceOnly = diagonal.contains("FACE");
                    boolean space = diagonal.contains("SPACE") || diagonal.contains("ALL");

                    // FACE diagonals: on each boundary face cell, add an alternating diagonal.
                    if (faceOnly || (!space && !diagonal.contains("SPACE"))) {
                        // XY faces (z = z0 / z1)
                        for (int zFace : new int[]{z0, z1}) {
                            for (int yiIdx = 0; yiIdx + 1 < ys.size(); yiIdx++) {
                                int ya = ys.get(yiIdx), yb = ys.get(yiIdx + 1);
                                for (int xiIdx = 0; xiIdx + 1 < xs.size(); xiIdx++) {
                                    int xa = xs.get(xiIdx), xb = xs.get(xiIdx + 1);
                                    if (((xiIdx + yiIdx) & 1) == 0) placeBeamLine(out, ctx, curOrigin, xa, ya, zFace, xb, yb, zFace, thick, 1, mat);
                                    else placeBeamLine(out, ctx, curOrigin, xb, ya, zFace, xa, yb, zFace, thick, 1, mat);
                                }
                            }
                        }
                        // YZ faces (x = x0 / x1)
                        for (int xFace : new int[]{x0, x1}) {
                            for (int yiIdx = 0; yiIdx + 1 < ys.size(); yiIdx++) {
                                int ya = ys.get(yiIdx), yb = ys.get(yiIdx + 1);
                                for (int ziIdx = 0; ziIdx + 1 < zs.size(); ziIdx++) {
                                    int za = zs.get(ziIdx), zb = zs.get(ziIdx + 1);
                                    if (((ziIdx + yiIdx) & 1) == 0) placeBeamLine(out, ctx, curOrigin, xFace, ya, za, xFace, yb, zb, thick, 1, mat);
                                    else placeBeamLine(out, ctx, curOrigin, xFace, ya, zb, xFace, yb, za, thick, 1, mat);
                                }
                            }
                        }
                        // XZ faces (y = y0 / y1)
                        for (int yFace : new int[]{y0, y1}) {
                            for (int ziIdx = 0; ziIdx + 1 < zs.size(); ziIdx++) {
                                int za = zs.get(ziIdx), zb = zs.get(ziIdx + 1);
                                for (int xiIdx = 0; xiIdx + 1 < xs.size(); xiIdx++) {
                                    int xa = xs.get(xiIdx), xb = xs.get(xiIdx + 1);
                                    if (((xiIdx + ziIdx) & 1) == 0) placeBeamLine(out, ctx, curOrigin, xa, yFace, za, xb, yFace, zb, thick, 1, mat);
                                    else placeBeamLine(out, ctx, curOrigin, xb, yFace, za, xa, yFace, zb, thick, 1, mat);
                                }
                            }
                        }
                    }

                    // SPACE diagonals: add a body diagonal within each grid cell (alternating).
                    if (space) {
                        for (int xiIdx = 0; xiIdx + 1 < xs.size(); xiIdx++) {
                            int xa = xs.get(xiIdx), xb = xs.get(xiIdx + 1);
                            for (int yiIdx = 0; yiIdx + 1 < ys.size(); yiIdx++) {
                                int ya = ys.get(yiIdx), yb = ys.get(yiIdx + 1);
                                for (int ziIdx = 0; ziIdx + 1 < zs.size(); ziIdx++) {
                                    int za = zs.get(ziIdx), zb = zs.get(ziIdx + 1);
                                    if (!all) {
                                        // For SURFACE mode, only interior body diagonals don't help; skip.
                                        continue;
                                    }
                                    if (((xiIdx + yiIdx + ziIdx) & 1) == 0) {
                                        placeBeamLine(out, ctx, curOrigin, xa, ya, za, xb, yb, zb, thick, 1, mat);
                                    } else {
                                        placeBeamLine(out, ctx, curOrigin, xb, ya, za, xa, yb, zb, thick, 1, mat);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            case "STAIR_SYSTEM" -> {
                // Stair system (P0): straight run staircase between from/to with optional carving for headroom.
                //
                // Required:
                // - from/to: {x,y,z} local points
                // Optional:
                // - width: stair width (default 2)
                // - clearHeight: carve clearance above each step (default 3)
                // - carve: whether to clear headroom (default true)
                // - support: place solid support blocks under each step (default true)
                // - stairs: block override for stairs (semantic STAIR)
                // - floor: landing/fill material for flat segments (semantic FLOORING)
                // - supportMaterial: semantic FOUNDATION (fallback floor)
                int[] a = AssemblyRasterOps.parsePoint(op.get("from"));
                int[] b = AssemblyRasterOps.parsePoint(op.get("to"));

                int width = clamp(i(op.get("width"), 2), 1, 15);
                boolean carve = bool(op.get("carve"), true);
                int clearH = clamp(i(op.get("clearHeight"), i(op.get("clear_h"), 3)), 0, 16);
                boolean support = bool(op.get("support"), true);

                BlockState stairMat = pick(ctx, op, "stairs", "STAIR", 0xA57470L, Blocks.STONE_BRICK_STAIRS.getDefaultState());
                BlockState floorMat = pick(ctx, op, "floor", "FLOORING", 0xA57471L, Blocks.SMOOTH_STONE.getDefaultState());
                BlockState supportMat = pick(ctx, op, "supportMaterial", "FOUNDATION", 0xA57472L, floorMat);

                int dx = b[0] - a[0];
                int dy = b[1] - a[1];
                int dz = b[2] - a[2];
                int run = Math.max(Math.max(Math.abs(dx), Math.abs(dz)), Math.abs(dy));
                run = Math.max(run, 1);

                // Determine main horizontal direction for stair facing (dominant axis)
                Direction horizDir;
                if (Math.abs(dx) >= Math.abs(dz)) horizDir = (dx >= 0) ? Direction.EAST : Direction.WEST;
                else horizDir = (dz >= 0) ? Direction.SOUTH : Direction.NORTH;

                int prevX = a[0], prevY = a[1], prevZ = a[2];
                for (int i = 0; i <= run; i++) {
                    double t = i / (double) run;
                    int x = (int) Math.round(a[0] + dx * t);
                    int z = (int) Math.round(a[2] + dz * t);
                    int y = (int) Math.round(a[1] + dy * t);

                    // clamp to avoid >1 jumps (best-effort)
                    int deltaY = y - prevY;
                    if (deltaY > 1) y = prevY + 1;
                    if (deltaY < -1) y = prevY - 1;

                    // compute lateral axis for width
                    Direction lateral = (horizDir == Direction.EAST || horizDir == Direction.WEST) ? Direction.SOUTH : Direction.EAST;
                    int half = width / 2;

                    // place step/landing across width
                    for (int wOff = -half; wOff <= half; wOff++) {
                        int wx = x + lateral.getOffsetX() * wOff;
                        int wz = z + lateral.getOffsetZ() * wOff;

                        if (y > prevY) {
                            // ascending: stair placed at lower position (prev), facing toward direction
                            int sx = prevX + lateral.getOffsetX() * wOff;
                            int sz = prevZ + lateral.getOffsetZ() * wOff;
                            BlockState s = stairMat;
                            if (s.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING)) {
                                s = s.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, horizDir);
                            }
                            put(out, ctx, curOrigin, sx, prevY, sz, s);
                        } else if (y < prevY) {
                            // descending: stair at current position, facing opposite
                            BlockState s = stairMat;
                            Direction f = horizDir.getOpposite();
                            if (s.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING)) {
                                s = s.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, f);
                            }
                            put(out, ctx, curOrigin, wx, y, wz, s);
                        } else {
                            // flat: floor
                            put(out, ctx, curOrigin, wx, y, wz, floorMat);
                        }

                        // support column (simple fill down one block; P0)
                        if (support) {
                            put(out, ctx, curOrigin, wx, y - 1, wz, supportMat);
                        }

                        // carve headroom
                        if (carve && clearH > 0) {
                            for (int yy = 1; yy <= clearH; yy++) {
                                put(out, ctx, curOrigin, wx, y + yy, wz, Blocks.AIR.getDefaultState());
                            }
                        }
                    }

                    prevX = x; prevY = y; prevZ = z;
                }
            }
            case "BEZIER_SURFACE" -> AssemblySurfaceOps.applyBezierSurface(out, ctx, curOrigin, op, SURFACE_OPS_ADAPTER);
            case "BEZIER_SURFACE_SET" -> AssemblySurfaceOps.applyBezierSurfaceSet(out, ctx, curOrigin, op, SURFACE_OPS_ADAPTER);
            case "SURFACE_OFFSET" -> AssemblySurfaceOps.applySurfaceOffset(out, ctx, curOrigin, op, SURFACE_OPS_ADAPTER);
            case "IMPLICIT_FIELD" -> {
                // Implicit field isosurface (P0): voxel isosurface extraction by sign change across 6-neighbors.
                //
                // Required:
                // - kind: SPHERE / TORUS / METABALLS
                // - bounds: x0..x1,y0..y1,z0..z1 (or w/d/h centered)
                // Optional:
                // - iso: threshold (default 0)
                // - band: thickness around iso (default 0.75)
                // - material: semantic PRIMARY_STRUCTURE (fallback quartz)
                String kind = str(op.get("kind"), str(op.get("field"), "SPHERE")).trim().toUpperCase(Locale.ROOT);
                double iso = d(op.get("iso"), 0.0);
                double band = d(op.get("band"), d(op.get("thickness"), 0.75));
                if (band <= 0) band = 0.75;

                int x0 = i(op.get("x0"), Integer.MIN_VALUE);
                int x1 = i(op.get("x1"), Integer.MIN_VALUE);
                int y0 = i(op.get("y0"), Integer.MIN_VALUE);
                int y1 = i(op.get("y1"), Integer.MIN_VALUE);
                int z0 = i(op.get("z0"), Integer.MIN_VALUE);
                int z1 = i(op.get("z1"), Integer.MIN_VALUE);
                if (x0 == Integer.MIN_VALUE || x1 == Integer.MIN_VALUE || y0 == Integer.MIN_VALUE || y1 == Integer.MIN_VALUE || z0 == Integer.MIN_VALUE || z1 == Integer.MIN_VALUE) {
                    int w = i(op.get("w"), i(op.get("width"), 32));
                    int d0 = i(op.get("d"), i(op.get("depth"), 32));
                    int h = i(op.get("h"), i(op.get("height"), 32));
                    int hx = Math.max(1, w / 2);
                    int hz = Math.max(1, d0 / 2);
                    x0 = -hx; x1 = hx;
                    y0 = 0; y1 = Math.max(1, h);
                    z0 = -hz; z1 = hz;
                }
                BlockState mat = pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0x1A2B3C4DL, Blocks.QUARTZ_BLOCK.getDefaultState());

                // Precompute metaballs if any
                java.util.ArrayList<double[]> balls = readMetaballs(op.get("metaballs"));
                double cx = d(op.get("cx"), d(op.get("x"), 0.0));
                double cy = d(op.get("cy"), d(op.get("y"), 0.0));
                double cz = d(op.get("cz"), d(op.get("z"), 0.0));
                if (op.get("center") instanceof Map<?, ?> cm) {
                    cx = d(cm.get("x"), cx);
                    cy = d(cm.get("y"), cy);
                    cz = d(cm.get("z"), cz);
                }
                double r = d(op.get("r"), d(op.get("radius"), 10.0));
                double R = d(op.get("R"), d(op.get("majorR"), 12.0));
                double rr = d(op.get("r2"), d(op.get("minorR"), 4.0));

                // Evaluate at voxel centers (x+0.5,y+0.5,z+0.5)
                for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
                    for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                        for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                            double fx = x + 0.5;
                            double fy = y + 0.5;
                            double fz = z + 0.5;
                            double f = evalField(kind, fx, fy, fz, cx, cy, cz, r, R, rr, balls) - iso;
                            if (Math.abs(f) > band) continue;
                            // surface test: sign change with any neighbor (6-neighborhood)
                            boolean surface = false;
                            for (int[] d6 : DIR6) {
                                double f2 = evalField(kind, fx + d6[0], fy + d6[1], fz + d6[2], cx, cy, cz, r, R, rr, balls) - iso;
                                if ((f <= 0 && f2 > 0) || (f > 0 && f2 <= 0)) { surface = true; break; }
                            }
                            if (surface) put(out, ctx, curOrigin, x, y, z, mat);
                        }
                    }
                }
            }
            case "MARCHING_CUBES" -> {
                // Marching Cubes (P0): implemented as Marching Tetrahedra (small table) + triangle barycentric voxelization.
                //
                // Required:
                // - kind/field + bounds
                // Optional:
                // - iso (default 0), samples (triangle fill density; default 2), material
                String kind = str(op.get("kind"), str(op.get("field"), "SPHERE")).trim().toUpperCase(Locale.ROOT);
                double iso = d(op.get("iso"), 0.0);
                int fill = clamp(i(op.get("fill"), i(op.get("samples"), 2)), 1, 8);

                int x0 = i(op.get("x0"), Integer.MIN_VALUE);
                int x1 = i(op.get("x1"), Integer.MIN_VALUE);
                int y0 = i(op.get("y0"), Integer.MIN_VALUE);
                int y1 = i(op.get("y1"), Integer.MIN_VALUE);
                int z0 = i(op.get("z0"), Integer.MIN_VALUE);
                int z1 = i(op.get("z1"), Integer.MIN_VALUE);
                if (x0 == Integer.MIN_VALUE || x1 == Integer.MIN_VALUE || y0 == Integer.MIN_VALUE || y1 == Integer.MIN_VALUE || z0 == Integer.MIN_VALUE || z1 == Integer.MIN_VALUE) {
                    int w = i(op.get("w"), i(op.get("width"), 32));
                    int d0 = i(op.get("d"), i(op.get("depth"), 32));
                    int h = i(op.get("h"), i(op.get("height"), 32));
                    int hx = Math.max(1, w / 2);
                    int hz = Math.max(1, d0 / 2);
                    x0 = -hx; x1 = hx;
                    y0 = 0; y1 = Math.max(1, h);
                    z0 = -hz; z1 = hz;
                }
                BlockState mat = pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0x4D0C73A7L, Blocks.QUARTZ_BLOCK.getDefaultState());

                java.util.ArrayList<double[]> balls = readMetaballs(op.get("metaballs"));
                double cx = d(op.get("cx"), d(op.get("x"), 0.0));
                double cy = d(op.get("cy"), d(op.get("y"), 0.0));
                double cz = d(op.get("cz"), d(op.get("z"), 0.0));
                if (op.get("center") instanceof Map<?, ?> cm) {
                    cx = d(cm.get("x"), cx);
                    cy = d(cm.get("y"), cy);
                    cz = d(cm.get("z"), cz);
                }
                double r = d(op.get("r"), d(op.get("radius"), 10.0));
                double R = d(op.get("R"), d(op.get("majorR"), 12.0));
                double rr = d(op.get("r2"), d(op.get("minorR"), 4.0));

                int ax0 = Math.min(x0, x1), ax1 = Math.max(x0, x1);
                int ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
                int az0 = Math.min(z0, z1), az1 = Math.max(z0, z1);

                // For each cell (cube), build 8 corner values and run marching tetrahedra.
                for (int x = ax0; x < ax1; x++) {
                    for (int y = ay0; y < ay1; y++) {
                        for (int z = az0; z < az1; z++) {
                            double[][] p8 = cubeCorners(x, y, z);
                            double[] v8 = new double[8];
                            for (int i = 0; i < 8; i++) {
                                double[] p = p8[i];
                                v8[i] = evalField(kind, p[0], p[1], p[2], cx, cy, cz, r, R, rr, balls) - iso;
                            }
                            marchTetrahedra(out, ctx, curOrigin, p8, v8, mat, fill);
                        }
                    }
                }
            }
            case "REVOLVE_SURFACE" -> AssemblySurfaceOps.applyRevolveSurface(out, ctx, curOrigin, op, SURFACE_OPS_ADAPTER);
            case "LOFT_SURFACE" -> AssemblySurfaceOps.applyLoftSurface(out, ctx, curOrigin, op, SURFACE_OPS_ADAPTER);
            case "PATH_ROUTE" -> {
                // Reuse PathGenerator (supports DRAPE etc via terrainAdaptation in extra).
                int width = clamp(i(op.get("width"), i(op.get("thickness"), 3)), 1, 15);
                String style = str(op.get("style"), "default");

                int[] p0 = AssemblyRasterOps.parsePoint(op.get("from"));
                int[] p1 = AssemblyRasterOps.parsePoint(op.get("to"));
                PathSpec ps = new PathSpec();
                ps.setFrom(new PathSpec.Point(p0[0], p0[1], p0[2]));
                ps.setTo(new PathSpec.Point(p1[0], p1[1], p1[2]));
                ps.setWidth(width);
                ps.setStyle(style);

                java.util.Map<String, Object> extra = new java.util.HashMap<>();
                if (ctx.paletteId != null && !ctx.paletteId.isBlank()) extra.put("paletteId", ctx.paletteId);
                // passthrough terrainAdaptation for DRAPE config
                if (op.get("terrainAdaptation") != null) extra.put("terrainAdaptation", op.get("terrainAdaptation"));
                ps.setExtra(extra);

                GeneratedStructure gs = new PathGenerator().generate(ps, curOrigin, ctx.world);
                out.addAll(gs.getBlocks());
            }
            case "WALL_ROUTE" -> {
                // Build a wall along a line between two points using LinearWallInterpreter.
                int[] p0 = AssemblyRasterOps.parsePoint(op.get("from"));
                int[] p1 = AssemblyRasterOps.parsePoint(op.get("to"));
                int height = clamp(i(op.get("wallHeight"), i(op.get("height"), 10)), 4, 120);
                int thickness = clamp(i(op.get("wallThickness"), i(op.get("thickness"), 5)), 3, 31);
                int towerSpacing = clamp(i(op.get("towerSpacing"), 48), 8, 512);
                int foundationDepth = clamp(i(op.get("foundationDepth"), 3), 0, 16);
                int maxStep = clamp(i(op.get("maxStep"), 1), 0, 8);
                boolean followTerrain = bool(op.get("followTerrain"), true);
                boolean crenels = bool(op.get("crenels"), true);

                // If terrainAdaptation is provided (DRAPE), borrow its key parameters when explicit values are absent.
                Object ta = op.get("terrainAdaptation");
                if (ta instanceof Map<?, ?> tm) {
                    String mode = String.valueOf(tm.get("mode") == null ? "" : tm.get("mode")).trim().toUpperCase(Locale.ROOT);
                    if (mode.contains("DRAPE")) {
                        followTerrain = true;
                        if (!op.containsKey("maxStep") && tm.get("max_step_height") != null) {
                            maxStep = clamp(i(tm.get("max_step_height"), maxStep), 0, 8);
                        }
                        if (!op.containsKey("foundationDepth") && tm.get("foundation_depth") != null) {
                            foundationDepth = clamp(i(tm.get("foundation_depth"), foundationDepth), 0, 16);
                        }
                    }
                }

                BlockState wall = pick(ctx, op, "wall", "WALL_BASE", 0xA56001L, Blocks.STONE_BRICKS.getDefaultState());
                BlockState accent = pick(ctx, op, "accent", "DECOR_DETAIL", 0xA56002L, Blocks.MOSSY_STONE_BRICKS.getDefaultState());
                BlockState walkway = pick(ctx, op, "walkway", "FLOORING", 0xA56003L, wall);
                BlockState walkwayStairs = pick(ctx, op, "walkwayStairs", "STAIRS", 0xA56004L, Blocks.STONE_BRICK_STAIRS.getDefaultState());
                BlockState crenel = pick(ctx, op, "crenel", "DECOR_DETAIL", 0xA56005L, Blocks.STONE_BRICK_WALL.getDefaultState());
                BlockState tower = pick(ctx, op, "tower", "WALL_BASE", 0xA56006L, wall);
                BlockState foundation = pick(ctx, op, "foundation", "WALL_FOUNDATION", 0xA56007L, wall);

                // World positions for endpoints
                BlockPos a = curOrigin.add(p0[0], p0[1], p0[2]);
                BlockPos b = curOrigin.add(p1[0], p1[1], p1[2]);
                List<BlockPos> pts = AssemblyRasterOps.rasterizeLine2D(a, b, ctx.world, followTerrain, maxStep);
                LinearPathPlan plan = new LinearPathPlan(pts, thickness, height, towerSpacing, crenels);
                List<PlannedBlock> wblocks = new LinearWallInterpreter(
                        wall, accent, false, walkway, walkwayStairs, crenel, tower, ctx.paletteId,
                        foundationDepth, foundation, true, true
                ).interpret(plan, curOrigin, ctx.world);
                out.addAll(wblocks);
            }
            case "BRIDGE_ROUTE" -> {
                // Axis-aligned: reuse BridgeGenerator (better aesthetics + anchor mode support).
                int[] p0 = AssemblyRasterOps.parsePoint(op.get("from"));
                int[] p1 = AssemblyRasterOps.parsePoint(op.get("to"));
                int width = clamp(i(op.get("width"), 5), 3, 25);

                int dx = p1[0] - p0[0];
                int dz = p1[2] - p0[2];
                boolean axisAligned = (dx == 0) ^ (dz == 0);
                if (axisAligned) {
                    int length = Math.max(Math.abs(dx), Math.abs(dz));
                    Direction forward = dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST : dz > 0 ? Direction.SOUTH : Direction.NORTH;

                    BuildingSpec bs = new BuildingSpec();
                    bs.setType(BuildingType.BRIDGE);
                    Footprint fp = new Footprint();
                    fp.setShape("rectangle");
                    fp.setWidth(width);
                    fp.setDepth(length);
                    bs.setFootprint(fp);
                    bs.setHeight(12);
                    java.util.Map<String, Object> extra = new java.util.HashMap<>();
                    extra.put("paletteId", ctx.paletteId);
                    extra.put("layout", java.util.Map.of("entranceFacing", forward.asString().toUpperCase(Locale.ROOT)));
                    // enable anchor if requested
                    if (op.get("terrainAdaptation") != null) extra.put("terrainAdaptation", op.get("terrainAdaptation"));
                    bs.setExtra(extra);

                    List<PlannedBlock> bBlocks = new BridgeGenerator().generate(bs, curOrigin.add(p0[0], p0[1], p0[2]), ctx.world).getBlocks();
                    out.addAll(bBlocks);
                } else {
                    // fallback: simple deck beam + pillars down (still respects "anchor" aesthetic roughly)
                    BlockState deck = pick(ctx, op, "deck", "BRIDGE_DECK", 0xA56101L, Blocks.OAK_PLANKS.getDefaultState());
                    BlockState rail = pick(ctx, op, "rail", "BRIDGE_RAIL", 0xA56102L, Blocks.OAK_FENCE.getDefaultState());
                    BlockState pier = pick(ctx, op, "pier", "WALL_FOUNDATION", 0xA56103L, Blocks.COBBLESTONE.getDefaultState());
                    BlockPos a = curOrigin.add(p0[0], p0[1], p0[2]);
                    BlockPos b = curOrigin.add(p1[0], p1[1], p1[2]);

                    List<BlockPos> center = AssemblyRasterOps.rasterizeLine2D(a, b, ctx.world, false, 0);
                    int deckY = Math.max(a.getY(), b.getY());
                    // if y not provided, choose above highest ground along line
                    if (p0[1] == 0 && p1[1] == 0) {
                        int max = deckY;
                        for (BlockPos p : center) {
                            int top = ctx.world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, p.getX(), p.getZ());
                            max = Math.max(max, top + 2);
                        }
                        deckY = max;
                    }
                    int absDx = Math.abs(b.getX() - a.getX());
                    int absDz = Math.abs(b.getZ() - a.getZ());
                    boolean widthAlongZ = absDx >= absDz; // mostly X => widen along Z; mostly Z => widen along X

                    for (BlockPos p : center) {
                        // deck width
                        for (int wOff = -width / 2; wOff <= width / 2; wOff++) {
                            BlockPos dp = widthAlongZ
                                    ? p.add(0, deckY - p.getY(), wOff)
                                    : p.add(wOff, deckY - p.getY(), 0);
                            out.add(new PlannedBlock(dp, deck));
                            // rail edges
                            if (Math.abs(wOff) == width / 2) out.add(new PlannedBlock(dp.up(), rail));
                            // pier down every 5 blocks
                            if ((p.getManhattanDistance(a) % 5) == 0 && wOff == 0) {
                                int y = deckY - 1;
                                for (int k = 0; k < 128; k++) {
                                    BlockPos pp = new BlockPos(dp.getX(), y - k, dp.getZ());
                                    BlockState cur = ctx.world.getBlockState(pp);
                                    if (!cur.isAir()) break;
                                    out.add(new PlannedBlock(pp, pier));
                                }
                            }
                        }
                    }
                }
            }
            case "EXTRUDE_POLYGON" -> {
                // Extrude a 2D polygon (x,z) into a prism.
                // Supported:
                // - shape: "RECT" with w,d (centered)
                // - points: [{x,z}, ...] (local)
                int h = clamp(i(op.get("h"), i(op.get("height"), 12)), 1, 255);
                boolean hollow = bool(op.get("hollow"), false);
                int thickness = clamp(i(op.get("thickness"), 1), 1, 16);

                List<int[]> pts = new ArrayList<>();
                String shape = str(op.get("shape"), "RECT").trim().toUpperCase(Locale.ROOT);
                if ("RECT".equals(shape)) {
                    int w = clamp(i(op.get("w"), 11), 1, 255);
                    int d = clamp(i(op.get("d"), 11), 1, 255);
                    int hx = w / 2;
                    int hz = d / 2;
                    pts.add(new int[]{-hx, -hz});
                    pts.add(new int[]{hx, -hz});
                    pts.add(new int[]{hx, hz});
                    pts.add(new int[]{-hx, hz});
                } else {
                    Object pointsObj = op.get("points");
                    if (pointsObj instanceof List<?> list) {
                        for (Object p : list) {
                            if (p instanceof Map<?, ?> pm) {
                                int px = i(pm.get("x"), 0);
                                int pz = i(pm.get("z"), 0);
                                pts.add(new int[]{px, pz});
                            }
                        }
                    }
                    if (pts.size() < 3) return curOrigin;
                }

                BlockState solid = pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0xA55401L, Blocks.STONE_BRICKS.getDefaultState());
                BlockState wall = pick(ctx, op, "wall", "WALL_BASE", 0xA55402L, solid);

                int[] bb = bounds(pts);
                int xMin = bb[0], xMax = bb[1], zMin = bb[2], zMax = bb[3];

                for (int x = xMin; x <= xMax; x++) {
                    for (int z = zMin; z <= zMax; z++) {
                        boolean inside = AssemblyProfilePolygonOps.pointInPolyXZ(x, z, pts);
                        if (!inside) continue;
                        for (int y = 0; y < h; y++) {
                            if (!hollow) {
                                put(out, ctx, curOrigin, x, y, z, solid);
                            } else {
                                // Keep a wall band by checking if any neighbor is outside within thickness.
                                boolean boundary = false;
                                for (int k = 1; k <= thickness && !boundary; k++) {
                                    if (!AssemblyProfilePolygonOps.pointInPolyXZ(x + k, z, pts)
                                            || !AssemblyProfilePolygonOps.pointInPolyXZ(x - k, z, pts)
                                            || !AssemblyProfilePolygonOps.pointInPolyXZ(x, z + k, pts)
                                            || !AssemblyProfilePolygonOps.pointInPolyXZ(x, z - k, pts)) {
                                        boundary = true;
                                    }
                                }
                                if (boundary) put(out, ctx, curOrigin, x, y, z, wall);
                                else put(out, ctx, curOrigin, x, y, z, Blocks.AIR.getDefaultState());
                            }
                        }
                    }
                }
            }
            case "ROOF_COVER" -> {
                // Simple roof cover placed above a footprint (centered around current origin).
                // type: FLAT / GABLE
                String type = str(op.get("type"), "FLAT").trim().toUpperCase(Locale.ROOT);
                int w = clamp(i(op.get("w"), 11), 3, 255);
                int d = clamp(i(op.get("d"), 11), 3, 255);
                int yBase = i(op.get("y"), 0);
                int overhang = clamp(i(op.get("overhang"), 0), 0, 8);
                int rise = clamp(i(op.get("rise"), Math.max(2, Math.min(6, Math.max(w, d) / 6))), 1, 32);
                // Forma-Gene integration: curvature power (0.0=linear, >0=concave curve, <0=convex curve)
                double curvaturePower = clamp(d(op.get("curvaturePower"), d(op.get("curvature_power"), 1.0)), 0.1, 3.0);
                // Forma-Gene integration: corner lift factor (0.0=none, >0=lift corners for Chinese/Japanese eaves)
                double cornerLift = clamp(d(op.get("cornerLift"), d(op.get("corner_lift"), 0.0)), 0.0, 2.0);

                BlockState roof = pick(ctx, op, "roof", "ROOF_TILE", 0xA55501L, Blocks.STONE_BRICK_STAIRS.getDefaultState());
                BlockState slab = pick(ctx, op, "slab", "FLOOR_SLAB", 0xA55502L, Blocks.SMOOTH_STONE_SLAB.getDefaultState());

                int hx = w / 2;
                int hz = d / 2;
                int x0 = -hx - overhang, x1 = hx + overhang;
                int z0 = -hz - overhang, z1 = hz + overhang;

                if (type.contains("FLAT")) {
                    for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) put(out, ctx, curOrigin, x, yBase, z, slab);
                    break;
                }

                // GABLE: ridge runs along the longer axis; roof slopes down to both sides.
                boolean ridgeAlongX = w >= d;
                if (!(roof.getBlock() instanceof StairsBlock)) {
                    // If roof isn't stairs, still place it as solid-ish cover.
                    for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) put(out, ctx, curOrigin, x, yBase, z, roof);
                    break;
                }

                if (ridgeAlongX) {
                    // slope along Z with curvature power
                    int spanZ = z1 - z0;
                    for (int step = 0; step < rise; step++) {
                        double t = (double) step / Math.max(1, rise - 1); // 0..1
                        // Apply curvature power: t^power (concave curve for power>1, linear for power=1)
                        double curvedT = Math.pow(t, curvaturePower);
                        int y = yBase + (int) Math.round(curvedT * rise);
                        int zz0 = z0 + (int) Math.round(curvedT * spanZ / 2);
                        int zz1 = z1 - (int) Math.round(curvedT * spanZ / 2);
                        if (zz0 > zz1) break;
                        
                        // Corner lift: add extra height at corners (x0/x1 positions) - max at mid-rise
                        int cornerLiftY = (int) Math.round(cornerLift * (1.0 - Math.abs(t - 0.5) * 2.0));
                        
                        Direction faceNorth = Direction.NORTH;
                        Direction faceSouth = Direction.SOUTH;
                        BlockState sN = roof.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING) ? roof.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, faceNorth) : roof;
                        BlockState sS = roof.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING) ? roof.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, faceSouth) : roof;
                        for (int x = x0; x <= x1; x++) {
                            int finalY = y + (cornerLiftY > 0 && (x == x0 || x == x1) ? cornerLiftY : 0);
                            put(out, ctx, curOrigin, x, finalY, zz0, sS);
                            put(out, ctx, curOrigin, x, finalY, zz1, sN);
                        }
                    }
                } else {
                    // slope along X with curvature power
                    int spanX = x1 - x0;
                    for (int step = 0; step < rise; step++) {
                        double t = (double) step / Math.max(1, rise - 1);
                        double curvedT = Math.pow(t, curvaturePower);
                        int y = yBase + (int) Math.round(curvedT * rise);
                        int xx0 = x0 + (int) Math.round(curvedT * spanX / 2);
                        int xx1 = x1 - (int) Math.round(curvedT * spanX / 2);
                        if (xx0 > xx1) break;
                        
                        // Corner lift
                        int cornerLiftY = (int) Math.round(cornerLift * (1.0 - Math.abs(t - 0.5) * 2.0));
                        
                        Direction faceWest = Direction.WEST;
                        Direction faceEast = Direction.EAST;
                        BlockState sW = roof.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING) ? roof.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, faceWest) : roof;
                        BlockState sE = roof.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING) ? roof.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, faceEast) : roof;
                        for (int z = z0; z <= z1; z++) {
                            int finalY = y + (cornerLiftY > 0 && (z == z0 || z == z1) ? cornerLiftY : 0);
                            put(out, ctx, curOrigin, xx0, finalY, z, sE);
                            put(out, ctx, curOrigin, xx1, finalY, z, sW);
                        }
                    }
                }
            }
            case "BSP_FLOOR_PLAN" -> {
                // expected fields:
                // - footprint: {w,d} or direct w/d
                // - height: h
                // - floor_plan_logic: { ... } or config: { ... }
                int w = clamp(i(op.get("w"), 19), 7, 129);
                int d = clamp(i(op.get("d"), 19), 7, 129);
                int h = clamp(i(op.get("h"), 30), 8, 255);

                Object cfgObj = op.get("floor_plan_logic");
                if (cfgObj == null) cfgObj = op.get("config");
                if (cfgObj == null) cfgObj = op.get("floorPlanLogic");
                FloorPlanConfig fpc = FloorPlanConfig.fromExtra(cfgObj);
                if (fpc == null) return curOrigin;

                BlockState coreWall = pick(ctx, op, "coreWall", "FRAME", 0xA55101L, Blocks.STONE_BRICKS.getDefaultState());
                BlockState roomWall;
                if (fpc.partitionStyle != null && fpc.partitionStyle.contains("OPEN")) {
                    roomWall = pick(ctx, op, "roomWallOpen", "PARTITION_WALL", 0xA55102L, Blocks.GLASS_PANE.getDefaultState());
                } else {
                    roomWall = pick(ctx, op, "roomWall", "PARTITION_WALL", 0xA55103L, coreWall);
                }
                BlockState stairs = pick(ctx, op, "stairs", "STAIRS", 0xA55104L, Blocks.STONE_BRICK_STAIRS.getDefaultState());

                BspFloorPlanGenerator.apply(
                        out,
                        curOrigin,
                        ctx.world,
                        w,
                        d,
                        h,
                        fpc,
                        BspFloorPlanGenerator.Materials.of(coreWall, roomWall, stairs)
                );
            }
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

    private static java.util.ArrayList<double[]> readMetaballs(Object obj) {
        java.util.ArrayList<double[]> out = new java.util.ArrayList<>();
        if (!(obj instanceof List<?> list)) return out;
        for (Object it : list) {
            if (!(it instanceof Map<?, ?> m)) continue;
            double x = d(m.get("x"), 0.0);
            double y = d(m.get("y"), 0.0);
            double z = d(m.get("z"), 0.0);
            double r = d(m.get("r"), d(m.get("radius"), 6.0));
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
                // Torus around Y axis. R=major radius, rr=minor radius.
                double dx = x - cx, dy = y - cy, dz = z - cz;
                double qx = Math.sqrt(dx * dx + dz * dz) - R;
                yield Math.sqrt(qx * qx + dy * dy) - rr;
            }
            case "METABALLS", "METABALL" -> {
                // Simple metaballs: sum of (ri^2 / di^2) - 1; iso at 0.
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
                yield (1.0 - s); // <=0 inside blobs
            }
            default -> {
                double dx = x - cx, dy = y - cy, dz = z - cz;
                yield Math.sqrt(dx * dx + dy * dy + dz * dz) - r;
            }
        };
    }

    private static double[][] cubeCorners(int x, int y, int z) {
        // standard cube corner ordering (0..7)
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

    private static final int[][] TETS = new int[][]{
            // 6-tet decomposition of a cube
            {0, 5, 1, 6},
            {0, 1, 2, 6},
            {0, 2, 3, 6},
            {0, 3, 7, 6},
            {0, 7, 4, 6},
            {0, 4, 5, 6}
    };

    private static void marchTetrahedra(List<PlannedBlock> out,
                                        Context ctx,
                                        BlockPos origin,
                                        double[][] p8,
                                        double[] v8,
                                        BlockState mat,
                                        int fill) {
        for (int[] tet : TETS) {
            int a = tet[0], b = tet[1], c = tet[2], d = tet[3];
            double va = v8[a], vb = v8[b], vc = v8[c], vd = v8[d];
            boolean ia = va <= 0, ib = vb <= 0, ic = vc <= 0, id = vd <= 0;
            int inside = (ia ? 1 : 0) + (ib ? 1 : 0) + (ic ? 1 : 0) + (id ? 1 : 0);
            if (inside == 0 || inside == 4) continue;

            int[] ids = new int[]{a, b, c, d};
            boolean[] ins = new boolean[]{ia, ib, ic, id};
            // collect inside/outside indices within tet-local 0..3
            int[] inIdx = new int[4];
            int[] outIdx = new int[4];
            int ni = 0, no = 0;
            for (int i = 0; i < 4; i++) {
                if (ins[i]) inIdx[ni++] = i;
                else outIdx[no++] = i;
            }

            if (inside == 1 || inside == 3) {
                // 1 triangle
                int vi = (inside == 1) ? inIdx[0] : outIdx[0];
                int vj = (inside == 1) ? outIdx[0] : inIdx[0];
                int vk = (inside == 1) ? outIdx[1] : inIdx[1];
                int vl = (inside == 1) ? outIdx[2] : inIdx[2];

                double[] p0 = interpIso(p8[ids[vi]], p8[ids[vj]], v8[ids[vi]], v8[ids[vj]]);
                double[] p1 = interpIso(p8[ids[vi]], p8[ids[vk]], v8[ids[vi]], v8[ids[vk]]);
                double[] p2 = interpIso(p8[ids[vi]], p8[ids[vl]], v8[ids[vi]], v8[ids[vl]]);
                voxelizeTriangle(out, ctx, origin, p0, p1, p2, mat, fill);
            } else if (inside == 2) {
                // 2 triangles (quad split)
                int v0 = inIdx[0], v1 = inIdx[1];
                int v2 = outIdx[0], v3 = outIdx[1];
                double[] p02 = interpIso(p8[ids[v0]], p8[ids[v2]], v8[ids[v0]], v8[ids[v2]]);
                double[] p03 = interpIso(p8[ids[v0]], p8[ids[v3]], v8[ids[v0]], v8[ids[v3]]);
                double[] p12 = interpIso(p8[ids[v1]], p8[ids[v2]], v8[ids[v1]], v8[ids[v2]]);
                double[] p13 = interpIso(p8[ids[v1]], p8[ids[v3]], v8[ids[v1]], v8[ids[v3]]);
                voxelizeTriangle(out, ctx, origin, p02, p12, p03, mat, fill);
                voxelizeTriangle(out, ctx, origin, p12, p13, p03, mat, fill);
            }
        }
    }

    private static double[] interpIso(double[] p0, double[] p1, double v0, double v1) {
        double t;
        double dv = v1 - v0;
        if (Math.abs(dv) < 1e-9) t = 0.5;
        else t = (0.0 - v0) / dv;
        t = Math.max(0.0, Math.min(1.0, t));
        return new double[]{
                AssemblyBezierOps.lerp(p0[0], p1[0], t),
                AssemblyBezierOps.lerp(p0[1], p1[1], t),
                AssemblyBezierOps.lerp(p0[2], p1[2], t)
        };
    }

    private static void voxelizeTriangle(List<PlannedBlock> out,
                                         Context ctx,
                                         BlockPos origin,
                                         double[] a,
                                         double[] b,
                                         double[] c,
                                         BlockState mat,
                                         int fill) {
        if (a == null || b == null || c == null) return;
        // barycentric grid sampling (P0): fill controls density; higher => smoother but heavier.
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
                put(out, ctx, origin, (int) Math.round(x), (int) Math.round(y), (int) Math.round(z), mat);
            }
        }
    }

    private static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static int[] bounds(List<int[]> pts) {
        int xMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE, zMin = Integer.MAX_VALUE, zMax = Integer.MIN_VALUE;
        for (int[] p : pts) {
            xMin = Math.min(xMin, p[0]);
            xMax = Math.max(xMax, p[0]);
            zMin = Math.min(zMin, p[1]);
            zMax = Math.max(zMax, p[1]);
        }
        return new int[]{xMin, xMax, zMin, zMax};
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
