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
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MetaAssemblyEngine (v1):
 * Executes an AssemblySpec ops list into PlannedBlocks.
 *
 * This is intentionally conservative: opt-in only (extra.assembly).
 */
public final class MetaAssemblyEngine {
    public record Context(ServerWorld world,
                          BlockPos origin,
                          Direction entranceFacing,
                          String paletteId) {}

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
                int[] p0 = parsePoint(op.get("from"));
                int[] p1 = parsePoint(op.get("to"));
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
                List<BlockPos> line = rasterizeLine2D(a, b, null, false, 0);
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
                int[] p0 = parsePoint(op.get("from"));
                int[] p1 = parsePoint(op.get("to"));
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
                int[] p0 = parsePoint(op.get("from"));
                int[] p1 = parsePoint(op.get("to"));

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
                int[] p0 = parsePoint(op.get("from"));
                int[] p1 = parsePoint(op.get("to"));

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
                int[] a = parsePoint(op.get("from"));
                int[] b = parsePoint(op.get("to"));

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
            case "BEZIER_SURFACE" -> {
                // Bezier surface patch (P0): 4x4 cubic Bezier surface, voxelized as a shell.
                //
                // Required:
                // - points: either 16 control points [{x,y,z}...] or 4 rows of 4 points [[{x,y,z}..]..]
                // Optional:
                // - uSamples/vSamples: sampling density (default 24/24)
                // - thickness: voxel thickness (default 1)
                // - connectSamples: connect adjacent samples with beams (default true)
                // - connectMaxStep: reserved (currently unused; default 2)
                // - material: semantic PRIMARY_STRUCTURE (fallback quartz)
                Object ptsObj = op.get("points");
                List<int[]> ctrl = readBezierControlPoints(ptsObj);
                if (ctrl == null || ctrl.size() != 16) break;

                int uN = clamp(i(op.get("uSamples"), i(op.get("u"), 24)), 2, 512);
                int vN = clamp(i(op.get("vSamples"), i(op.get("v"), 24)), 2, 512);
                int thick = clamp(i(op.get("thickness"), 1), 1, 9);
                boolean connect = bool(op.get("connectSamples"), true);

                BlockState mat = pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0xA57480L, Blocks.QUARTZ_BLOCK.getDefaultState());

                // Sample grid and place points; optionally connect adjacent samples to reduce gaps.
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
                        int xi = (int) Math.round(x);
                        int yi = (int) Math.round(y);
                        int zi = (int) Math.round(z);
                        grid[iu][iv][0] = xi;
                        grid[iu][iv][1] = yi;
                        grid[iu][iv][2] = zi;
                        placePrism(out, ctx, curOrigin, xi, yi, zi, thick, 1, mat);
                    }
                }
                if (connect) {
                    for (int iu = 0; iu <= uN; iu++) {
                        for (int iv = 0; iv <= vN; iv++) {
                            int x = grid[iu][iv][0], y = grid[iu][iv][1], z = grid[iu][iv][2];
                            if (iu + 1 <= uN) {
                                int[] b = grid[iu + 1][iv];
                                placeBeamLine(out, ctx, curOrigin, x, y, z, b[0], b[1], b[2], thick, 1, mat);
                            }
                            if (iv + 1 <= vN) {
                                int[] b = grid[iu][iv + 1];
                                placeBeamLine(out, ctx, curOrigin, x, y, z, b[0], b[1], b[2], thick, 1, mat);
                            }
                        }
                    }
                }
            }
            case "BEZIER_SURFACE_SET" -> {
                // Bezier surface set (P0): multiple patches with grid topology and auto-stitching on shared edges.
                //
                // Required:
                // - patches: [{ id?, at?, points: (16 control points), uSamples?, vSamples?, thickness?, material?, connectSamples? }, ...]
                // Optional:
                // - topology.grid: 2D array of patch ids/indices (preferred) or grid (legacy)
                // - uSamples/vSamples, thickness, connectSamples (intra-patch), stitch (inter-patch), material
                Object patchesObj = op.get("patches");
                if (!(patchesObj instanceof List<?> patchesList) || patchesList.isEmpty()) break;

                int uDef = clamp(i(op.get("uSamples"), i(op.get("u"), 24)), 2, 512);
                int vDef = clamp(i(op.get("vSamples"), i(op.get("v"), 24)), 2, 512);
                int thickDef = clamp(i(op.get("thickness"), 1), 1, 9);
                boolean connectDef = bool(op.get("connectSamples"), true);
                boolean stitch = bool(op.get("stitch"), true);
                int stitchEps = clamp(i(op.get("stitchEpsilon"), i(op.get("stitch_eps"), 0)), 0, 32);
                int stitchSamples = clamp(i(op.get("stitchSamples"), i(op.get("stitch_samples"), -1)), -1, 512);
                String stitchResampleMode = str(op.get("stitchResampleMode"), str(op.get("stitch_resample_mode"), "RESAMPLE")).trim().toUpperCase(Locale.ROOT);
                boolean stitchResample = stitchResampleMode.isBlank() || stitchResampleMode.equals("RESAMPLE") || stitchResampleMode.equals("AUTO");
                BlockState matDef = pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0xBEEF1101L, Blocks.QUARTZ_BLOCK.getDefaultState());
                int capWidthDef = clamp(i(op.get("capWidth"), i(op.get("cap_width"), 0)), 0, 9);
                BlockState capMatDef = pick(ctx, op, "capMaterial", "FACADE_TRIM", 0xBEEF1102L, matDef);

                // Build patch table
                java.util.HashMap<String, PatchData> byId = new java.util.HashMap<>();
                java.util.ArrayList<PatchData> patches = new java.util.ArrayList<>();
                java.util.HashMap<String, SeamRef> seamMap = new java.util.HashMap<>();
                java.util.ArrayList<SeamRef> seamList = new java.util.ArrayList<>();
                for (int pi = 0; pi < patchesList.size(); pi++) {
                    Object po = patchesList.get(pi);
                    if (!(po instanceof Map<?, ?> pm)) continue;
                    String id = str(pm.get("id"), "P" + pi).trim();

                    // Per-patch offset
                    int ox, oy, oz;
                    Object at = pm.get("at");
                    if (at instanceof Map<?, ?> am) {
                        ox = i(am.get("x"), 0);
                        oy = i(am.get("y"), 0);
                        oz = i(am.get("z"), 0);
                    } else {
                        ox = i(pm.get("x"), 0);
                        oy = i(pm.get("y"), 0);
                        oz = i(pm.get("z"), 0);
                    }

                    List<int[]> ctrl0 = readBezierControlPoints(pm.get("points"));
                    if (ctrl0 == null || ctrl0.size() != 16) continue;
                    java.util.ArrayList<int[]> ctrl = new java.util.ArrayList<>(16);
                    for (int[] p : ctrl0) ctrl.add(new int[]{p[0] + ox, p[1] + oy, p[2] + oz});

                    int uN = clamp(i(pm.get("uSamples"), i(pm.get("u"), uDef)), 2, 512);
                    int vN = clamp(i(pm.get("vSamples"), i(pm.get("v"), vDef)), 2, 512);
                    int thick = clamp(i(pm.get("thickness"), thickDef), 1, 9);
                    boolean connect = bool(pm.get("connectSamples"), connectDef);
                    BlockState mat = (pm.get("material") != null)
                            ? pick(ctx, pm, "material", "PRIMARY_STRUCTURE", 0xBEEF1101L ^ id.hashCode(), matDef)
                            : matDef;

                    int[][][] grid = sampleBezierSurface(ctrl, uN, vN);
                    PatchData pd = new PatchData(id, uN, vN, thick, mat, grid);
                    patches.add(pd);
                    byId.put(id, pd);
                    // place voxels
                    for (int iu = 0; iu <= uN; iu++) for (int iv = 0; iv <= vN; iv++) {
                        int x = grid[iu][iv][0], y = grid[iu][iv][1], z = grid[iu][iv][2];
                        placePrism(out, ctx, curOrigin, x, y, z, thick, 1, mat);
                    }
                    if (connect) {
                        connectSurfaceGrid(out, ctx, curOrigin, grid, uN, vN, thick, mat);
                    }

                    // Auto-stitch shared edges by matching sampled boundary signatures.
                    if (stitch) {
                        for (Edge e : Edge.values()) {
                            String sig = edgeSignature(pd, e, false);
                            String sigR = edgeSignature(pd, e, true);
                            SeamRef other = seamMap.remove(sigR);
                            if (other == null) {
                                seamMap.put(sig, new SeamRef(pd, e));
                            } else {
                                int seamThick = Math.min(other.patch.thick, pd.thick);
                                stitchEdge(out, ctx, curOrigin, other.patch, other.edge, pd, e, seamThick, matDef, capWidthDef, capMatDef);
                            }
                            // epsilon path keeps an explicit list too (for near-matches)
                            seamList.add(new SeamRef(pd, e));
                        }
                    }
                }

                if (!stitch) break;

                // Epsilon stitching: allow near-matches by sampling + thresholding.
                if (stitchEps > 0 && seamList.size() >= 2) {
                    // Greedy pairing: try to stitch each edge with the best unmatched partner.
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
                            // quick endpoint gate
                            int[] b0 = edgePoint(b.patch, b.edge, 0);
                            int[] b1 = edgePoint(b.patch, b.edge, edgeCount(b.patch, b.edge) - 1);
                            long eps2 = (long) stitchEps * stitchEps;
                            long d00 = dist2(a0, b0) + dist2(a1, b1);
                            long d01 = dist2(a0, b1) + dist2(a1, b0);
                            boolean rev = d01 < d00;
                            long dGate = Math.min(d00, d01);
                            if (dGate > eps2 * 4L) continue;

                            int nA = edgeCount(a.patch, a.edge);
                            int nB = edgeCount(b.patch, b.edge);
                            int n = (stitchSamples > 0) ? stitchSamples : Math.max(8, Math.min(128, Math.max(nA, nB)));
                            double mse = edgeMse(a.patch, a.edge, b.patch, b.edge, rev, n, stitchResample);
                            if (mse < best) { best = mse; bestJ = j; bestReverse = rev; }
                        }
                        if (bestJ >= 0) {
                            SeamRef b = seamList.get(bestJ);
                            // accept if within epsilon (mean squared distance <= eps^2)
                            if (best <= (double) stitchEps * stitchEps) {
                                int seamThick = Math.min(a.patch.thick, b.patch.thick);
                                stitchEdgeResampled(out, ctx, curOrigin, a.patch, a.edge, b.patch, b.edge, bestReverse,
                                        seamThick, matDef, (stitchSamples > 0) ? stitchSamples : -1, stitchResample, capWidthDef, capMatDef);
                                used.add(ka);
                                long kb = (((long) b.patch.hashCode()) << 8) ^ b.edge.ordinal();
                                used.add(kb);
                            }
                        }
                    }
                }

                // Derive adjacency from grid topology if provided.
                Object gridObj = null;
                Object topo = op.get("topology");
                if (topo instanceof Map<?, ?> tm) gridObj = tm.get("grid");
                if (gridObj == null) gridObj = op.get("grid"); // legacy

                // Explicit links topology (non-grid): stitch declared edges.
                if (topo instanceof Map<?, ?> tm2 && tm2.get("links") instanceof List<?> links) {
                    for (int li = 0; li < links.size(); li++) {
                        Object lo = links.get(li);
                        if (!(lo instanceof Map<?, ?> lm)) continue;
                        String aId = str(lm.get("a"), str(lm.get("from"), "")).trim();
                        String bId = str(lm.get("b"), str(lm.get("to"), "")).trim();
                        String eaS = str(lm.get("ea"), str(lm.get("edgeA"), str(lm.get("fromEdge"), ""))).trim().toUpperCase(Locale.ROOT);
                        String ebS = str(lm.get("eb"), str(lm.get("edgeB"), str(lm.get("toEdge"), ""))).trim().toUpperCase(Locale.ROOT);
                        PatchData a = resolvePatch(byId, patches, aId);
                        PatchData b = resolvePatch(byId, patches, bId);
                        if (a == null || b == null) continue;
                        Edge ea = parseEdge(eaS);
                        Edge eb = parseEdge(ebS);
                        if (ea == null || eb == null) continue;

                        int linkEps = clamp(i(lm.get("epsilon"), i(lm.get("stitchEpsilon"), stitchEps)), 0, 64);
                        int linkSamples = clamp(i(lm.get("samples"), i(lm.get("stitchSamples"), stitchSamples)), -1, 512);
                        String linkMode = str(lm.get("resampleMode"), str(lm.get("stitchResampleMode"), stitchResampleMode)).trim().toUpperCase(Locale.ROOT);
                        boolean linkResample = linkMode.isBlank() || linkMode.equals("RESAMPLE") || linkMode.equals("AUTO");
                        int linkThick = clamp(i(lm.get("thickness"), Math.min(a.thick, b.thick)), 1, 9);

                        // T-junction / partial edge stitching: optional sub-range mapping on edges (0..1).
                        double[] ar = parseRange01(lm.get("aRange"), lm.get("a_range"), lm.get("fromRange"));
                        double[] br = parseRange01(lm.get("bRange"), lm.get("b_range"), lm.get("toRange"));
                        double a0t = (ar != null) ? ar[0] : 0.0;
                        double a1t = (ar != null) ? ar[1] : 1.0;
                        double b0t = (br != null) ? br[0] : 0.0;
                        double b1t = (br != null) ? br[1] : 1.0;

                        // Determine orientation via endpoint distance (same as stitchEdge) + optional epsilon gate.
                        int[] a0 = edgePointAtRange(a, ea, 0.0, a0t, a1t);
                        int[] a1 = edgePointAtRange(a, ea, 1.0, a0t, a1t);
                        int[] b0 = edgePointAtRange(b, eb, 0.0, b0t, b1t);
                        int[] b1 = edgePointAtRange(b, eb, 1.0, b0t, b1t);
                        boolean reverse = (dist2(a0, b1) + dist2(a1, b0)) < (dist2(a0, b0) + dist2(a1, b1));
                        if (linkEps > 0) {
                            double n = (linkSamples > 0) ? linkSamples : 64;
                            double mse = edgeMseRange(a, ea, a0t, a1t, b, eb, b0t, b1t, reverse, (int) n, linkResample);
                            if (mse > (double) linkEps * linkEps) continue;
                        }
                        int linkCapW = clamp(i(lm.get("capWidth"), i(lm.get("cap_width"), capWidthDef)), 0, 9);
                        BlockState linkCapMat = (lm.get("capMaterial") != null)
                                ? pick(ctx, lm, "capMaterial", "FACADE_TRIM", 0xBEEF1202L ^ (li * 1315423911), capMatDef)
                                : capMatDef;
                        stitchEdgeResampledRange(out, ctx, curOrigin, a, ea, a0t, a1t, b, eb, b0t, b1t, reverse, linkThick, matDef,
                                (linkSamples > 0) ? linkSamples : -1, linkResample, linkCapW, linkCapMat);
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
                    // grid[row][col] neighbors:
                    // - right: A.U1 <-> B.U0
                    // - down:  A.V1 <-> B.V0
                    for (int r = 0; r < ids.size(); r++) {
                        for (int c = 0; c < ids.get(r).size(); c++) {
                            String aId = ids.get(r).get(c);
                            PatchData a = resolvePatch(byId, patches, aId);
                            if (a == null) continue;
                            if (c + 1 < ids.get(r).size()) {
                                PatchData b = resolvePatch(byId, patches, ids.get(r).get(c + 1));
                                if (b != null) stitchEdge(out, ctx, curOrigin, a, Edge.U1, b, Edge.U0, Math.min(a.thick, b.thick), matDef, capWidthDef, capMatDef);
                            }
                            if (r + 1 < ids.size() && c < ids.get(r + 1).size()) {
                                PatchData b = resolvePatch(byId, patches, ids.get(r + 1).get(c));
                                if (b != null) stitchEdge(out, ctx, curOrigin, a, Edge.V1, b, Edge.V0, Math.min(a.thick, b.thick), matDef, capWidthDef, capMatDef);
                            }
                        }
                    }
                }
            }
            case "SURFACE_OFFSET" -> {
                // Surface offset thick shell (P0): approximate normals from a sampled surface grid and offset voxels along normal.
                //
                // Required:
                // - source: { kind:"BEZIER_SURFACE", points:[...] } or { kind:"BEZIER_SURFACE_SET", patches:[...] }
                // Optional:
                // - uSamples/vSamples: sampling density for source (default 24/24)
                // - offset: steps along normal (default 0)
                // - shellThickness: thickness along normal (default 2)
                // - mode: OUT/IN/BOTH (default BOTH)
                // - material: semantic PRIMARY_STRUCTURE (fallback quartz)
                Object srcObj = op.get("source");
                if (!(srcObj instanceof Map<?, ?> sm)) break;
                String kind = String.valueOf(sm.get("kind") == null ? "" : sm.get("kind")).trim().toUpperCase(Locale.ROOT);
                int uN = clamp(i(op.get("uSamples"), i(op.get("u"), i(sm.get("uSamples"), i(sm.get("u"), 24)))), 2, 512);
                int vN = clamp(i(op.get("vSamples"), i(op.get("v"), i(sm.get("vSamples"), i(sm.get("v"), 24)))), 2, 512);
                int offset = clamp(i(op.get("offset"), i(op.get("distance"), 0)), -32, 32);
                int shellT = clamp(i(op.get("shellThickness"), i(op.get("thickness"), 2)), 1, 16);
                String mode = str(op.get("mode"), "BOTH").trim().toUpperCase(Locale.ROOT);
                String normalMode = str(op.get("normalMode"), str(op.get("normal_mode"), "DDA")).trim().toUpperCase(Locale.ROOT);
                double stepLen = Math.max(0.25, Math.min(4.0, d(op.get("stepLen"), d(op.get("step_len"), d(op.get("step"), 1.0)))));
                boolean dedupe = bool(op.get("dedupe"), bool(op.get("deDupe"), true));
                boolean connect = bool(op.get("connectSamples"), bool(op.get("connect_samples"), false));
                int connectMaxStep = clamp(i(op.get("connectMaxStep"), i(op.get("connect_max_step"), 2)), 1, 16);
                BlockState mat = pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0x51AFC0L, Blocks.QUARTZ_BLOCK.getDefaultState());

                if (kind.equals("BEZIER_SURFACE")) {
                    List<int[]> ctrl = readBezierControlPoints(sm.get("points"));
                    if (ctrl == null || ctrl.size() != 16) break;
                    int[][][] grid = sampleBezierSurface(ctrl, uN, vN);
                    surfaceOffsetFromGrid(out, ctx, curOrigin, grid, uN, vN, offset, shellT, mode, normalMode, stepLen, dedupe, connect, connectMaxStep, mat);
                } else if (kind.equals("BEZIER_SURFACE_SET")) {
                    Object patchesObj = sm.get("patches");
                    if (!(patchesObj instanceof List<?> pl) || pl.isEmpty()) break;
                    for (Object po : pl) {
                        if (!(po instanceof Map<?, ?> pm)) continue;
                        // apply patch at offset if present
                        int ox, oy, oz;
                        Object at = pm.get("at");
                        if (at instanceof Map<?, ?> am) {
                            ox = i(am.get("x"), 0);
                            oy = i(am.get("y"), 0);
                            oz = i(am.get("z"), 0);
                        } else {
                            ox = i(pm.get("x"), 0);
                            oy = i(pm.get("y"), 0);
                            oz = i(pm.get("z"), 0);
                        }
                        List<int[]> ctrl0 = readBezierControlPoints(pm.get("points"));
                        if (ctrl0 == null || ctrl0.size() != 16) continue;
                        java.util.ArrayList<int[]> ctrl = new java.util.ArrayList<>(16);
                        for (int[] p : ctrl0) ctrl.add(new int[]{p[0] + ox, p[1] + oy, p[2] + oz});
                        int[][][] grid = sampleBezierSurface(ctrl, uN, vN);
                        surfaceOffsetFromGrid(out, ctx, curOrigin, grid, uN, vN, offset, shellT, mode, normalMode, stepLen, dedupe, connect, connectMaxStep, mat);
                    }
                }
            }
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
            case "REVOLVE_SURFACE" -> {
                // Surface of revolution (P0): revolve a 2D profile around the Y axis.
                //
                // Required:
                // - profilePoints or profileRings: 2D points in (r,y) plane (x->r, y->y)
                // Optional:
                // - segments: angular segments (default 48)
                // - angleDeg: total revolution angle (default 360)
                // - thickness: voxel thickness (default 1)
                // - connectSamples: connect rings and vertical profile steps (default true)
                // - material: semantic PRIMARY_STRUCTURE (fallback quartz)
                Object profObj = op.get("profileRings");
                if (profObj == null) profObj = op.get("rings");
                if (profObj == null) profObj = op.get("profilePoints");
                if (profObj == null) profObj = op.get("points");
                List<int[]> profile = read2DProfilePoints(profObj);
                if (profile == null || profile.size() < 2) break;

                int seg = clamp(i(op.get("segments"), 48), 8, 512);
                double angleDeg = d(op.get("angleDeg"), d(op.get("angle"), 360.0));
                if (Double.isNaN(angleDeg) || angleDeg <= 0.0) angleDeg = 360.0;
                if (angleDeg > 360.0) angleDeg = 360.0;
                int thick = clamp(i(op.get("thickness"), 1), 1, 9);
                boolean connect = bool(op.get("connectSamples"), true);
                BlockState mat = pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0x93F3B1L, Blocks.QUARTZ_BLOCK.getDefaultState());

                int nTheta = (int) Math.round(seg * (angleDeg / 360.0));
                nTheta = clamp(nTheta, 3, 512);
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
                        placePrism(out, ctx, curOrigin, x, yy, z, thick, 1, mat);
                    }
                }
                if (connect) {
                    for (int it = 0; it <= nTheta; it++) {
                        for (int ip = 0; ip < nP; ip++) {
                            int x = grid[it][ip][0], y = grid[it][ip][1], z = grid[it][ip][2];
                            if (it + 1 <= nTheta) {
                                int[] b = grid[it + 1][ip];
                                placeBeamLine(out, ctx, curOrigin, x, y, z, b[0], b[1], b[2], thick, 1, mat);
                            }
                            if (ip + 1 < nP) {
                                int[] b = grid[it][ip + 1];
                                placeBeamLine(out, ctx, curOrigin, x, y, z, b[0], b[1], b[2], thick, 1, mat);
                            }
                        }
                    }
                }
            }
            case "LOFT_SURFACE" -> {
                // Loft surface (P0): connect multiple profile sections along a path (linear interpolation).
                //
                // Required:
                // - sections: [{ at:{x,y,z}, profilePoints:[{x,y}..] | profileRings:[...] }, ...] (>=2)
                // Optional:
                // - uSamples: interpolation steps between sections (default 24)
                // - thickness: voxel thickness (default 1)
                // - connectSamples: connect adjacent samples to reduce gaps (default true)
                // - material: semantic PRIMARY_STRUCTURE (fallback quartz)
                Object secObj = op.get("sections");
                if (!(secObj instanceof List<?> secs) || secs.size() < 2) break;

                List<LoftSection> sections = new ArrayList<>();
                for (Object sObj : secs) {
                    if (!(sObj instanceof Map<?, ?> sm)) continue;
                    Object atObj = sm.get("at");
                    int ax, ay, az;
                    if (atObj instanceof Map<?, ?> am) {
                        ax = i(am.get("x"), 0);
                        ay = i(am.get("y"), 0);
                        az = i(am.get("z"), 0);
                    } else {
                        ax = i(sm.get("x"), 0);
                        ay = i(sm.get("y"), 0);
                        az = i(sm.get("z"), 0);
                    }
                    Object profObj = sm.get("profileRings");
                    if (profObj == null) profObj = sm.get("rings");
                    if (profObj == null) profObj = sm.get("profilePoints");
                    List<int[]> prof = read2DProfilePoints(profObj);
                    if (prof == null || prof.size() < 2) continue;
                    sections.add(new LoftSection(ax, ay, az, prof));
                }
                if (sections.size() < 2) break;

                // P0 constraint: require consistent point counts (training stability)
                int nP = sections.getFirst().profile.size();
                boolean ok = true;
                for (LoftSection s : sections) if (s.profile.size() != nP) { ok = false; break; }
                if (!ok) break;

                int uN = clamp(i(op.get("uSamples"), i(op.get("u"), 24)), 2, 512);
                int thick = clamp(i(op.get("thickness"), 1), 1, 9);
                boolean connect = bool(op.get("connectSamples"), true);
                BlockState mat = pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0x0E11F3L, Blocks.QUARTZ_BLOCK.getDefaultState());

                // For each adjacent pair of sections, interpolate and draw strips.
                for (int si = 0; si < sections.size() - 1; si++) {
                    LoftSection a = sections.get(si);
                    LoftSection b = sections.get(si + 1);
                    int[][][] grid = new int[uN + 1][nP][3];
                    for (int iu = 0; iu <= uN; iu++) {
                        double t = iu / (double) uN;
                        double ox = lerp(a.x, b.x, t);
                        double oy = lerp(a.y, b.y, t);
                        double oz = lerp(a.z, b.z, t);
                        for (int ip = 0; ip < nP; ip++) {
                            int[] pa = a.profile.get(ip);
                            int[] pb = b.profile.get(ip);
                            double px = lerp(pa[0], pb[0], t);
                            double py = lerp(pa[1], pb[1], t);
                            int x = (int) Math.round(ox + px);
                            int y = (int) Math.round(oy + py);
                            int z = (int) Math.round(oz);
                            grid[iu][ip][0] = x;
                            grid[iu][ip][1] = y;
                            grid[iu][ip][2] = z;
                            placePrism(out, ctx, curOrigin, x, y, z, thick, 1, mat);
                        }
                    }
                    if (connect) {
                        for (int iu = 0; iu <= uN; iu++) {
                            for (int ip = 0; ip < nP; ip++) {
                                int x = grid[iu][ip][0], y = grid[iu][ip][1], z = grid[iu][ip][2];
                                if (iu + 1 <= uN) {
                                    int[] bb = grid[iu + 1][ip];
                                    placeBeamLine(out, ctx, curOrigin, x, y, z, bb[0], bb[1], bb[2], thick, 1, mat);
                                }
                                if (ip + 1 < nP) {
                                    int[] bb = grid[iu][ip + 1];
                                    placeBeamLine(out, ctx, curOrigin, x, y, z, bb[0], bb[1], bb[2], thick, 1, mat);
                                }
                            }
                        }
                    }
                }
            }
            case "PATH_ROUTE" -> {
                // Reuse PathGenerator (supports DRAPE etc via terrainAdaptation in extra).
                int width = clamp(i(op.get("width"), i(op.get("thickness"), 3)), 1, 15);
                String style = str(op.get("style"), "default");

                int[] p0 = parsePoint(op.get("from"));
                int[] p1 = parsePoint(op.get("to"));
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
                int[] p0 = parsePoint(op.get("from"));
                int[] p1 = parsePoint(op.get("to"));
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
                List<BlockPos> pts = rasterizeLine2D(a, b, ctx.world, followTerrain, maxStep);
                LinearPathPlan plan = new LinearPathPlan(pts, thickness, height, towerSpacing, crenels);
                List<PlannedBlock> wblocks = new LinearWallInterpreter(
                        wall, accent, false, walkway, walkwayStairs, crenel, tower, ctx.paletteId,
                        foundationDepth, foundation, true, true
                ).interpret(plan, curOrigin, ctx.world);
                out.addAll(wblocks);
            }
            case "BRIDGE_ROUTE" -> {
                // Axis-aligned: reuse BridgeGenerator (better aesthetics + anchor mode support).
                int[] p0 = parsePoint(op.get("from"));
                int[] p1 = parsePoint(op.get("to"));
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

                    List<BlockPos> center = rasterizeLine2D(a, b, ctx.world, false, 0);
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
                        boolean inside = pointInPoly(x, z, pts);
                        if (!inside) continue;
                        for (int y = 0; y < h; y++) {
                            if (!hollow) {
                                put(out, ctx, curOrigin, x, y, z, solid);
                            } else {
                                // Keep a wall band by checking if any neighbor is outside within thickness.
                                boolean boundary = false;
                                for (int k = 1; k <= thickness && !boundary; k++) {
                                    if (!pointInPoly(x + k, z, pts) || !pointInPoly(x - k, z, pts) || !pointInPoly(x, z + k, pts) || !pointInPoly(x, z - k, pts)) {
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
            case "SPLINE_SWEEP", "SPLINE_TUBE" -> {
                // Sweep a tube-like volume along a spline defined by control points (local coords).
                // Keys:
                // - points: [{x,y,z}, ...] (>=2)
                // - profile: "SPHERE"(default) / "RECT"
                // - profileW/profileH for RECT
                // - twistTurns: number of full turns along the sweep (RECT only; best-effort)
                // - r or radius (default 3)
                // - r0/r1 (taper endpoints, overrides r)
                // - hollow: bool
                // - thickness: shell thickness if hollow
                // - samplesPerBlock: density factor (default 10)
                List<Vec3d> pts = parseVecPoints(op.get("points"));
                if (pts.size() < 2) break;

                int samplesPerBlock = clamp(i(op.get("samplesPerBlock"), 10), 2, 40);
                List<Vec3d> poly = sampleBezierSpline(pts, samplesPerBlock);
                if (poly.size() < 2) break;

                String profile = str(op.get("profile"), "SPHERE").trim().toUpperCase(Locale.ROOT);
                String profileFrame = str(op.get("profileFrame"), str(op.get("frame"), "PATH")).trim().toUpperCase(Locale.ROOT);
                String snapMode = str(op.get("profileSnap"), str(op.get("snap"), "ROUND")).trim().toUpperCase(Locale.ROOT);
                int r = clamp(i(op.get("r"), i(op.get("radius"), 3)), 1, 24);
                int r0 = i(op.get("r0"), i(op.get("radius0"), Integer.MIN_VALUE));
                int r1 = i(op.get("r1"), i(op.get("radius1"), Integer.MIN_VALUE));
                boolean taper = (r0 != Integer.MIN_VALUE && r1 != Integer.MIN_VALUE);
                if (!taper) { r0 = r; r1 = r; }

                boolean hollow = bool(op.get("hollow"), false);
                int thickness = clamp(i(op.get("thickness"), 1), 1, 8);
                double twistTurns = d(op.get("twistTurns"), 0.0);
                double twistPhase = d(op.get("twistPhase"), 0.0);

                BlockState mat = pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0xA58001L, Blocks.WHITE_CONCRETE.getDefaultState());
                BlockState shell = pick(ctx, op, "wall", "WALL_BASE", 0xA58002L, mat);

                java.util.HashSet<Long> seen = new java.util.HashSet<>();
                boolean connectSamples = bool(op.get("connectSamples"), false);
                int connectMaxStep = clamp(i(op.get("connectMaxStep"), 2), 1, 8);
                java.util.HashMap<Long, long[]> lastSection = connectSamples ? new java.util.HashMap<>() : null; // keyUV -> [x,y,z]
                int n = poly.size();
                for (int i = 0; i < n; i++) {
                    double tt = (n <= 1) ? 0.0 : (i / (double) (n - 1));
                    double rad = lerp(r0, r1, tt);
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
                                    long key = packXYZ(x, y, z);
                                    if (!seen.add(key)) continue;
                                    put(out, ctx, curOrigin, x, y, z, hollow ? shell : mat);
                                }
                            }
                        }
                        continue;
                    }

                    // RECT profile: build a local frame from tangent + an "up" vector, then sweep a rectangle.
                    // POLYGON profile uses the same frame but fills a 2D polygon in the (nrm2,bin2) plane.
                    int pwConst = clamp(i(op.get("profileW"), i(op.get("w"), 5)), 1, 64);
                    int phConst = clamp(i(op.get("profileH"), i(op.get("h"), 3)), 1, 64);
                    int pw0 = i(op.get("profileW0"), i(op.get("w0"), Integer.MIN_VALUE));
                    int pw1 = i(op.get("profileW1"), i(op.get("w1"), Integer.MIN_VALUE));
                    int ph0 = i(op.get("profileH0"), i(op.get("h0"), Integer.MIN_VALUE));
                    int ph1 = i(op.get("profileH1"), i(op.get("h1"), Integer.MIN_VALUE));
                    boolean rectTaper = (pw0 != Integer.MIN_VALUE && pw1 != Integer.MIN_VALUE) || (ph0 != Integer.MIN_VALUE && ph1 != Integer.MIN_VALUE);
                    if (!rectTaper) {
                        pw0 = pwConst; pw1 = pwConst;
                        ph0 = phConst; ph1 = phConst;
                    } else {
                        if (pw0 == Integer.MIN_VALUE) pw0 = pwConst;
                        if (pw1 == Integer.MIN_VALUE) pw1 = pwConst;
                        if (ph0 == Integer.MIN_VALUE) ph0 = phConst;
                        if (ph1 == Integer.MIN_VALUE) ph1 = phConst;
                    }
                    int pw = clamp((int) Math.round(lerp(pw0, pw1, tt)), 1, 64);
                    int ph = clamp((int) Math.round(lerp(ph0, ph1, tt)), 1, 64);
                    int halfW = Math.max(0, pw / 2);
                    int halfH = Math.max(0, ph / 2);
                    // For hollow rect, thickness means border thickness in grid cells.
                    int t = Math.max(1, thickness);
                    boolean capEnds = bool(op.get("capEnds"), hollow);
                    boolean carveInterior = bool(op.get("carveInterior"), false);
                    int capThickness = clamp(i(op.get("capThickness"), t), 1, 8);

                    Vec3d prev = (i > 0) ? poly.get(i - 1) : poly.get(i);
                    Vec3d next = (i + 1 < n) ? poly.get(i + 1) : poly.get(i);
                    Vec3d tan = next.subtract(prev);
                    if (tan.lengthSquared() < 1e-6) tan = new Vec3d(0, 0, 1);
                    tan = tan.normalize();

                    // Choose a frame for the profile plane.
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
                            // PATH: build an orthonormal frame around tangent.
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
                        // profile can be:
                        // - profilePoints: single ring
                        // - profileRings: [ring0, ring1, ...] where ring0 is outer, others are holes
                        List<List<int[]>> rings2 = parseProfileRings(op);
                        if (rings2.isEmpty() || rings2.getFirst().size() < 3) break;
                        double s0 = d(op.get("profileScale0"), d(op.get("scale0"), 1.0));
                        double s1 = d(op.get("profileScale1"), d(op.get("scale1"), 1.0));
                        double sc = lerp(s0, s1, tt);
                        if (sc <= 0.05) sc = 0.05;
                        // bounds in profile space
                        int[] bb = boundsRings2D(rings2);
                        int uMin = (int) Math.floor(bb[0] * sc);
                        int uMax = (int) Math.ceil(bb[1] * sc);
                        int vMin = (int) Math.floor(bb[2] * sc);
                        int vMax = (int) Math.ceil(bb[3] * sc);
                        // safety cap
                        int area2d = (uMax - uMin + 1) * (vMax - vMin + 1);
                        if (area2d > 20000) break;

                        // scaled rings for point tests
                        List<List<int[]>> sr = scaleRings(rings2, sc);

                        for (int uu = uMin; uu <= uMax; uu++) {
                            for (int vv = vMin; vv <= vMax; vv++) {
                                boolean inside = pointInRings2D(uu, vv, sr);
                                if (!inside) continue;
                                boolean border = true;
                                if (hollow) {
                                    border = isRingsBorder(uu, vv, sr, t);
                                    if (!border && !carveInterior) continue;
                                }
                                Vec3d off = nrm2.multiply(uu).add(bin2.multiply(vv));
                                int x = cx + snap(off.x, snapMode);
                                int y = cy + snap(off.y, snapMode);
                                int z = cz + snap(off.z, snapMode);
                                if (connectSamples && lastSection != null) {
                                    BlockState s = (!hollow) ? mat : (border ? shell : Blocks.AIR.getDefaultState());
                                    connectToLast(out, ctx, curOrigin, lastSection, packUV(uu, vv), x, y, z, s, seen, connectMaxStep);
                                }
                                long key = packXYZ(x, y, z);
                                if (!seen.add(key)) continue;
                                if (!hollow) put(out, ctx, curOrigin, x, y, z, mat);
                                else put(out, ctx, curOrigin, x, y, z, border ? shell : Blocks.AIR.getDefaultState());
                            }
                        }

                        if (hollow && capEnds && (i == 0 || i == n - 1)) {
                            for (int uu = uMin; uu <= uMax; uu++) {
                                for (int vv = vMin; vv <= vMax; vv++) {
                                    boolean inside = pointInRings2D(uu, vv, sr);
                                    if (!inside) continue;
                                    boolean border = isRingsBorder(uu, vv, sr, capThickness);
                                    if (!border) continue;
                                    Vec3d off = nrm2.multiply(uu).add(bin2.multiply(vv));
                                    int x = cx + snap(off.x, snapMode);
                                    int y = cy + snap(off.y, snapMode);
                                    int z = cz + snap(off.z, snapMode);
                                    if (connectSamples && lastSection != null) {
                                        connectToLast(out, ctx, curOrigin, lastSection, packUV(uu, vv), x, y, z, shell, seen, connectMaxStep);
                                    }
                                    long key = packXYZ(x, y, z);
                                    if (!seen.add(key)) continue;
                                    put(out, ctx, curOrigin, x, y, z, shell);
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
                                connectToLast(out, ctx, curOrigin, lastSection, packUV(uu, vv), x, y, z, s, seen, connectMaxStep);
                            }
                            long key = packXYZ(x, y, z);
                            if (!seen.add(key)) continue;
                            if (!hollow) {
                                put(out, ctx, curOrigin, x, y, z, mat);
                            } else {
                                put(out, ctx, curOrigin, x, y, z, border ? shell : Blocks.AIR.getDefaultState());
                            }
                        }
                    }

                    // End caps: close the corridor opening at endpoints for hollow RECT sweeps.
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
                                    connectToLast(out, ctx, curOrigin, lastSection, packUV(uu, vv), x, y, z, shell, seen, connectMaxStep);
                                }
                                long key = packXYZ(x, y, z);
                                if (!seen.add(key)) continue;
                                put(out, ctx, curOrigin, x, y, z, shell);
                            }
                        }
                    }
                }
            }
            case "SURFACE_PATTERN" -> {
                // Apply an unstyled pattern on a box face.
                // Required:
                // - face: NORTH/SOUTH/EAST/WEST (local)
                // - x0..x1,y0..y1,z0..z1 bounds (local)
                // Optional:
                // - pattern: GRID / STRIPES_V / STRIPES_H / RIBS_V / RIBS_H / NOISE
                // - step: spacing (for GRID/STRIPES/RIBS)
                // - thickness: rib thickness (for RIBS)
                // Forma-Gene integration: NOISE pattern support
                // - noiseMaterial: material for noise overlay (default: accent)
                // - noiseProbability: probability 0.0~1.0 (default: 0.2)
                // - noiseMethod: "PERLIN" / "RANDOM" / "HASH" (default: "HASH")
                String face = str(op.get("face"), "NORTH").trim().toUpperCase(Locale.ROOT);
                String pattern = str(op.get("pattern"), "GRID").trim().toUpperCase(Locale.ROOT);
                int step = clamp(i(op.get("step"), i(op.get("spacing"), 3)), 1, 16);
                int thick = clamp(i(op.get("thickness"), 1), 1, 8);

                int x0 = i(op.get("x0"), 0), x1 = i(op.get("x1"), 0);
                int y0 = i(op.get("y0"), 1), y1 = i(op.get("y1"), 10);
                int z0 = i(op.get("z0"), 0), z1 = i(op.get("z1"), 0);
                int ax0 = Math.min(x0, x1), ax1 = Math.max(x0, x1);
                int ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
                int az0 = Math.min(z0, z1), az1 = Math.max(z0, z1);

                BlockState accent = pick(ctx, op, "material", "FACADE_ACCENT", 0xA57001L,
                        pick(ctx, op, "accent", "DECOR_DETAIL", 0xA57002L, Blocks.STONE_BRICK_WALL.getDefaultState()));

                // Forma-Gene integration: NOISE pattern support
                BlockState noiseMat = accent;
                if (pattern.equals("NOISE")) {
                    Object nmObj = op.get("noiseMaterial");
                    if (nmObj == null) nmObj = op.get("noise_material");
                    if (nmObj != null) {
                        BlockState parsed = parseBlockId(ctx.world, String.valueOf(nmObj).trim());
                        if (parsed != null) noiseMat = parsed;
                        else noiseMat = pick(ctx, op, "noiseMaterial", "FACADE_ACCENT", 0xA57003L, accent);
                    }
                }

                double noiseProb = 0.2;
                if (pattern.equals("NOISE")) {
                    Object npObj = op.get("noiseProbability");
                    if (npObj == null) npObj = op.get("noise_probability");
                    if (npObj != null) {
                        try {
                            double v = (npObj instanceof Number n) ? n.doubleValue() : Double.parseDouble(String.valueOf(npObj));
                            noiseProb = clamp(v, 0.0, 1.0);
                        } catch (Exception ignored) {}
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
                    applyPatternPlane(out, ctx, curOrigin, pattern, step, thick, accent, noiseMat, noiseProb, noiseMethod, ax0, ax1, ay0, ay1, az0, true);
                } else if ("SOUTH".equals(face)) {
                    applyPatternPlane(out, ctx, curOrigin, pattern, step, thick, accent, noiseMat, noiseProb, noiseMethod, ax0, ax1, ay0, ay1, az1, true);
                } else if ("WEST".equals(face)) {
                    applyPatternPlane(out, ctx, curOrigin, pattern, step, thick, accent, noiseMat, noiseProb, noiseMethod, az0, az1, ay0, ay1, ax0, false);
                } else if ("EAST".equals(face)) {
                    applyPatternPlane(out, ctx, curOrigin, pattern, step, thick, accent, noiseMat, noiseProb, noiseMethod, az0, az1, ay0, ay1, ax1, false);
                }
            }
            case "FACADE_GRID" -> {
                // Modular curtain wall facade grid (stronger than SURFACE_PATTERN.GRID):
                // Places mullions/transoms + optional panel fill material on a box face.
                //
                // Required:
                // - face: NORTH/SOUTH/EAST/WEST (local)
                // - x0..x1,y0..y1,z0..z1 bounds (local)
                // Optional:
                // - bayW/bayH: module size in U/Y (default 3/4)
                // - mullionThickness/transomThickness (default 1)
                // - borderThickness (default mullionThickness)
                // - marginU/marginY (default 1/1)
                // - inset (shift plane inward from the face; default 0)
                // - depth (extrude inward by N blocks; default 1)
                // - frame: mullion material (semantic FACADE_TRIM)
                // - fill: panel material (semantic WINDOW)
                // Spandrel (optional):
                // - spandrelEvery: repeat period in Y blocks (e.g., 4 for each floor module)
                // - spandrelHeight: band height in blocks within each period
                // - spandrelOffset: offset in blocks within the period
                // - spandrelFill: fill material for spandrel zones (semantic WALL_BASE)
                String face = str(op.get("face"), str(op.get("faces"), "NORTH")).trim().toUpperCase(Locale.ROOT);
                int bayW = clamp(i(op.get("bayW"), i(op.get("moduleW"), i(op.get("gridW"), 3))), 1, 32);
                int bayH = clamp(i(op.get("bayH"), i(op.get("moduleH"), i(op.get("gridH"), 4))), 1, 32);
                int mullionT = clamp(i(op.get("mullionThickness"), i(op.get("mullionT"), 1)), 0, 8);
                int transomT = clamp(i(op.get("transomThickness"), i(op.get("transomT"), 1)), 0, 8);
                int borderT = clamp(i(op.get("borderThickness"), i(op.get("borderT"), mullionT)), 0, 8);
                int marginU = clamp(i(op.get("marginU"), i(op.get("marginX"), 1)), 0, 64);
                int marginY = clamp(i(op.get("marginY"), 1), 0, 64);
                int inset = clamp(i(op.get("inset"), 0), 0, 16);
                int depth = clamp(i(op.get("depth"), 1), 1, 8);

                int spEvery = clamp(i(op.get("spandrelEvery"), i(op.get("spEvery"), 0)), 0, 128);
                int spH = clamp(i(op.get("spandrelHeight"), i(op.get("spH"), 0)), 0, 64);
                int spOff = clamp(i(op.get("spandrelOffset"), i(op.get("spOffset"), 0)), 0, 128);

                int x0 = i(op.get("x0"), 0), x1 = i(op.get("x1"), 0);
                int y0 = i(op.get("y0"), 1), y1 = i(op.get("y1"), 10);
                int z0 = i(op.get("z0"), 0), z1 = i(op.get("z1"), 0);
                int ax0 = Math.min(x0, x1), ax1 = Math.max(x0, x1);
                int ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
                int az0 = Math.min(z0, z1), az1 = Math.max(z0, z1);

                BlockState frame = pick(ctx, op, "frame", "FACADE_TRIM", 0xA57201L, Blocks.SMOOTH_STONE.getDefaultState());
                BlockState fill = pick(ctx, op, "fill", "WINDOW", 0xA57202L, Blocks.GLASS_PANE.getDefaultState());
                BlockState spFill = pick(ctx, op, "spandrelFill", "WALL_BASE", 0xA57203L, fill);

                // If caller passes "ALL" / comma faces, expand here for direct ops usage.
                for (String f : expandFacesLocal(face)) {
                    if ("NORTH".equals(f)) {
                        applyFacadeGridPlane(out, ctx, curOrigin, bayW, bayH, mullionT, transomT, borderT, marginU, marginY, frame, fill, spFill,
                                spEvery, spH, spOff,
                                ax0, ax1, ay0, ay1, az0 + inset, true, depth, +1);
                    } else if ("SOUTH".equals(f)) {
                        applyFacadeGridPlane(out, ctx, curOrigin, bayW, bayH, mullionT, transomT, borderT, marginU, marginY, frame, fill, spFill,
                                spEvery, spH, spOff,
                                ax0, ax1, ay0, ay1, az1 - inset, true, depth, -1);
                    } else if ("WEST".equals(f)) {
                        applyFacadeGridPlane(out, ctx, curOrigin, bayW, bayH, mullionT, transomT, borderT, marginU, marginY, frame, fill, spFill,
                                spEvery, spH, spOff,
                                az0, az1, ay0, ay1, ax0 + inset, false, depth, +1);
                    } else if ("EAST".equals(f)) {
                        applyFacadeGridPlane(out, ctx, curOrigin, bayW, bayH, mullionT, transomT, borderT, marginU, marginY, frame, fill, spFill,
                                spEvery, spH, spOff,
                                az0, az1, ay0, ay1, ax1 - inset, false, depth, -1);
                    }
                }
            }
            case "SURFACE_BANDS" -> {
                // Surface bands macro: horizontal cornices/belt-lines + vertical columns/ribs on a face.
                //
                // Required:
                // - face: NORTH/SOUTH/EAST/WEST (local) (can also be ALL / "NORTH,EAST" when used as direct ops)
                // - x0..x1,y0..y1,z0..z1 bounds (local)
                // Optional:
                // - horizontalBands: [{ y, height, material, inset?, outset?, depth? }, ...]
                // - verticalBands:   [{ step, width, offset?, y0?, y1?, material, inset?, outset?, depth? }, ...]
                String face = str(op.get("face"), str(op.get("faces"), "NORTH")).trim().toUpperCase(Locale.ROOT);
                int x0 = i(op.get("x0"), 0), x1 = i(op.get("x1"), 0);
                int y0 = i(op.get("y0"), 1), y1 = i(op.get("y1"), 10);
                int z0 = i(op.get("z0"), 0), z1 = i(op.get("z1"), 0);
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

                    // Horizontal bands
                    if (hbObj instanceof List<?> hbList) {
                        for (Object it : hbList) {
                            if (!(it instanceof Map<?, ?> bm)) continue;
                            int by = i(bm.get("y"), Integer.MIN_VALUE);
                            if (by == Integer.MIN_VALUE) by = i(bm.get("atY"), Integer.MIN_VALUE);
                            if (by == Integer.MIN_VALUE) continue;
                            int bh = clamp(i(bm.get("height"), i(bm.get("h"), 1)), 1, 32);
                            int inset = clamp(i(bm.get("inset"), 0), 0, 16);
                            int outset = clamp(i(bm.get("outset"), i(bm.get("out"), 0)), 0, 16);
                            int depth = clamp(i(bm.get("depth"), 1), 1, 8);
                            BlockState mat = pick(ctx, bm, "material", "FACADE_ACCENT", 0xA57301L, Blocks.STONE_BRICK_SLAB.getDefaultState());

                            int yy0 = Math.max(ay0, by);
                            int yy1 = Math.min(ay1, by + bh - 1);
                            if (yy0 > yy1) continue;
                            applyBandPlane(out, ctx, curOrigin, u0, u1, yy0, yy1, fixed + inwardSign * inset - inwardSign * outset, uIsX, depth, inwardSign, mat);
                        }
                    }

                    // Vertical bands (columns/ribs)
                    if (vbObj instanceof List<?> vbList) {
                        for (Object it : vbList) {
                            if (!(it instanceof Map<?, ?> bm)) continue;
                            int step = clamp(i(bm.get("step"), i(bm.get("spacing"), 4)), 1, 64);
                            int width = clamp(i(bm.get("width"), i(bm.get("thickness"), 1)), 1, 16);
                            int offset = i(bm.get("offset"), 0);
                            int yy0 = i(bm.get("y0"), ay0);
                            int yy1 = i(bm.get("y1"), ay1);
                            yy0 = Math.max(ay0, Math.min(ay1, yy0));
                            yy1 = Math.max(ay0, Math.min(ay1, yy1));
                            if (yy0 > yy1) { int t = yy0; yy0 = yy1; yy1 = t; }
                            int inset = clamp(i(bm.get("inset"), 0), 0, 16);
                            int outset = clamp(i(bm.get("outset"), i(bm.get("out"), 0)), 0, 16);
                            int depth = clamp(i(bm.get("depth"), 1), 1, 8);
                            BlockState mat = pick(ctx, bm, "material", "FACADE_TRIM", 0xA57302L, Blocks.SMOOTH_STONE.getDefaultState());

                            applyVerticalBandsPlane(out, ctx, curOrigin, u0, u1, yy0, yy1, fixed + inwardSign * inset - inwardSign * outset, uIsX, depth, inwardSign, step, width, offset, mat);
                        }
                    }
                }
            }
            case "OPENINGS" -> {
                // Carve/open a set of windows/doors on a face of a box.
                // Required:
                // - face: NORTH/SOUTH/EAST/WEST (local)
                // - kind: WINDOW_GRID / DOOR / ARCH_WINDOW / ROSE_WINDOW
                // - x0..x1,y0..y1,z0..z1 bounds
                String face = str(op.get("face"), "NORTH").trim().toUpperCase(Locale.ROOT);
                String kind = str(op.get("kind"), "WINDOW_GRID").trim().toUpperCase(Locale.ROOT);

                int x0 = i(op.get("x0"), 0), x1 = i(op.get("x1"), 0);
                int y0 = i(op.get("y0"), 0), y1 = i(op.get("y1"), 18);
                int z0 = i(op.get("z0"), 0), z1 = i(op.get("z1"), 0);
                int ax0 = Math.min(x0, x1), ax1 = Math.max(x0, x1);
                int ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
                int az0 = Math.min(z0, z1), az1 = Math.max(z0, z1);

                BlockState fill = pick(ctx, op, "fill", "WINDOW", 0xA57101L, Blocks.GLASS_PANE.getDefaultState());
                BlockState frame = pick(ctx, op, "frame", "FACADE_TRIM", 0xA57102L, Blocks.SMOOTH_STONE.getDefaultState());
                BlockState air = Blocks.AIR.getDefaultState();

                int frameT = clamp(i(op.get("frameThickness"), 1), 0, 4);
                int mullionStep = clamp(i(op.get("mullionStep"), 0), 0, 16);

                if (kind.contains("DOOR")) {
                    int doorW = clamp(i(op.get("doorW"), i(op.get("winW"), 2)), 1, 7);
                    int doorH = clamp(i(op.get("doorH"), i(op.get("winH"), 3)), 2, 10);
                    int sx = (ax0 + ax1) / 2;
                    int sz = (az0 + az1) / 2;
                    carveRectOnFace(out, ctx, curOrigin, face, sx, sz, ay0, doorW, doorH, air, frame, frameT, mullionStep);
                    break;
                }

                if (kind.contains("ROSE")) {
                    // Rose window approximation: ring + spokes + fill.
                    int r = clamp(i(op.get("r"), i(op.get("radius"), 5)), 2, 24);
                    int ring = clamp(i(op.get("ring"), i(op.get("frameThickness"), 1)), 1, 6);
                    int petals = clamp(i(op.get("petals"), i(op.get("spokes"), 8)), 3, 32);
                    int spokeWidth = clamp(i(op.get("spokeWidth"), i(op.get("spokeW"), 1)), 1, 6);
                    double phase = d(op.get("phase"), d(op.get("phi"), 0.0));
                    double spokeThreshold = d(op.get("spokeThreshold"), d(op.get("spokeThresh"), 0.06));
                    int cy = i(op.get("centerY"), -999999);
                    if (cy <= -999000) cy = ay0 + (ay1 - ay0) * 2 / 3;
                    int cx = (ax0 + ax1) / 2;
                    int cz = (az0 + az1) / 2;
                    // If phase looks like 0..1, treat it as turns.
                    if (phase >= 0.0 && phase <= 1.0) phase = phase * (Math.PI * 2.0);
                    if (spokeThreshold < 0.0) spokeThreshold = 0.0;
                    if (spokeThreshold > 0.25) spokeThreshold = 0.25;
                    BlockState innerFill = pick(ctx, op, "innerFill", "WINDOW", 0xA57111L, fill);
                    BlockState spokeMat = pick(ctx, op, "spokeMaterial", "FACADE_TRIM", 0xA57112L, frame);
                    carveRoseOnFace(out, ctx, curOrigin, face, cx, cz, cy, r, ring, petals, phase, spokeWidth, spokeThreshold, fill, innerFill, frame, spokeMat);
                    break;
                }

                int rows = clamp(i(op.get("rows"), 2), 1, 12);
                int cols = clamp(i(op.get("cols"), 3), 1, 24);
                int winW = clamp(i(op.get("winW"), 2), 1, 9);
                int winH = clamp(i(op.get("winH"), 3), 1, 12);
                int sillY = clamp(i(op.get("sillY"), 2), ay0, ay1);
                int marginX = clamp(i(op.get("marginX"), 2), 0, 64);
                int marginY = clamp(i(op.get("marginY"), 2), 0, 64);
                int gapX = clamp(i(op.get("gapX"), 2), 0, 32);
                int gapY = clamp(i(op.get("gapY"), 2), 0, 32);

                boolean spanAlongX = "NORTH".equals(face) || "SOUTH".equals(face);
                int spanMin = spanAlongX ? ax0 : az0;
                int spanMax = spanAlongX ? ax1 : az1;
                int span = spanMax - spanMin + 1;
                int usable = Math.max(0, span - marginX * 2);
                int totalW = cols * winW + Math.max(0, cols - 1) * gapX;
                if (totalW <= 0) break;
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
                            String archType = str(op.get("archType"), str(op.get("arch"), "ROUND")).trim().toUpperCase(Locale.ROOT);
                            int archThickness = clamp(i(op.get("archThickness"), i(op.get("archT"), frameT)), 0, 6);
                            BlockState keystone = pick(ctx, op, "keystone", "FACADE_TRIM", 0xA57121L, frame);
                            boolean keystoneOn = bool(op.get("keystoneOn"), true);
                            String tracery = str(op.get("tracery"), str(op.get("traceryType"), "")).trim().toUpperCase(Locale.ROOT);
                            int traceryThickness = clamp(i(op.get("traceryThickness"), i(op.get("traceryT"), 1)), 0, 6);
                            int traceryY = i(op.get("traceryY"), Integer.MIN_VALUE); // optional absolute local Y
                            int traceryInset = clamp(i(op.get("traceryInset"), 0), 0, 2);
                            int foilRadius = i(op.get("traceryFoilRadius"), i(op.get("foilRadius"), 0));
                            int foilCount = resolveFoilCount(op, winH);
                            int foilStepY = i(op.get("traceryFoilStepY"), i(op.get("foilStepY"), i(op.get("foilGapY"), 0)));
                            boolean foilStepAuto = isAuto(op.get("foilStepY")) || isAuto(op.get("foilGapY")) || isAuto(op.get("traceryFoilStepY"));
                            BlockState traceryMat = pick(ctx, op, "traceryMaterial", "FACADE_TRIM", 0xA57122L, frame);
                            int foilCenterY = resolveFoilCenterY(op, yBase, winH);
                            carveArchOnFace(out, ctx, curOrigin, face, centerX, centerZ, yBase, winW, winH, archType, fill, frame, frameT, mullionStep,
                                    archThickness, keystoneOn ? keystone : null,
                                    tracery, traceryMat, traceryThickness, traceryY, traceryInset, foilRadius, foilCenterY, foilCount, foilStepY, foilStepAuto);
                        } else {
                            carveRectOnFace(out, ctx, curOrigin, face, centerX, centerZ, yBase, winW, winH, fill, frame, frameT, mullionStep);
                        }
                    }
                }
            }
            default -> {
                // ignore unknown ops for forward compatibility
            }
        }
        return curOrigin;
    }

    private static void applyPatternPlane(List<PlannedBlock> out,
                                          Context ctx,
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
                                          boolean uIsX) {
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
                        // Forma-Gene integration: noise overlay
                        double noiseValue;
                        int x = uIsX ? u : fixed;
                        int z = uIsX ? fixed : u;
                        long seed = (long) x * 73856093L ^ (long) y * 19349663L ^ (long) z * 83492791L;
                        
                        if ("PERLIN".equals(noiseMethod)) {
                            // Simple pseudo-Perlin using hash-based interpolation
                            noiseValue = simplePerlinNoise(x, y, z, seed);
                        } else if ("RANDOM".equals(noiseMethod)) {
                            // Pure random based on coordinates
                            java.util.Random rng = new java.util.Random(seed);
                            noiseValue = rng.nextDouble();
                        } else {
                            // HASH (default): deterministic hash-based noise
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
                put(out, ctx, origin, x, y, z, mat);
            }
        }
    }

    // Simple hash-based noise (0.0~1.0)
    private static double hashNoise(int x, int y, int z, long seed) {
        long h = seed;
        h ^= (long) x * 73856093L;
        h ^= (long) y * 19349663L;
        h ^= (long) z * 83492791L;
        h = h * (h * h * 15731L + 789221L) + 1376312589L;
        h = h ^ (h >>> 16);
        return ((h & 0x7FFFFFFFL) % 10000) / 10000.0;
    }

    // Simple pseudo-Perlin noise (0.0~1.0)
    private static double simplePerlinNoise(int x, int y, int z, long seed) {
        // Simplified Perlin-like noise using hash interpolation
        double fx = x * 0.1;
        double fy = y * 0.1;
        double fz = z * 0.1;
        
        int ix = (int) Math.floor(fx);
        int iy = (int) Math.floor(fy);
        int iz = (int) Math.floor(fz);
        
        double fx0 = fx - ix;
        double fy0 = fy - iy;
        double fz0 = fz - iz;
        
        // Smooth interpolation
        double ux = fx0 * fx0 * (3.0 - 2.0 * fx0);
        double uy = fy0 * fy0 * (3.0 - 2.0 * fy0);
        double uz = fz0 * fz0 * (3.0 - 2.0 * fz0);
        
        // Hash values at 8 corners
        double n000 = hashNoise(ix, iy, iz, seed);
        double n100 = hashNoise(ix + 1, iy, iz, seed);
        double n010 = hashNoise(ix, iy + 1, iz, seed);
        double n110 = hashNoise(ix + 1, iy + 1, iz, seed);
        double n001 = hashNoise(ix, iy, iz + 1, seed);
        double n101 = hashNoise(ix + 1, iy, iz + 1, seed);
        double n011 = hashNoise(ix, iy + 1, iz + 1, seed);
        double n111 = hashNoise(ix + 1, iy + 1, iz + 1, seed);
        
        // Trilinear interpolation
        double nx00 = lerp(n000, n100, ux);
        double nx10 = lerp(n010, n110, ux);
        double nx01 = lerp(n001, n101, ux);
        double nx11 = lerp(n011, n111, ux);
        double nxy0 = lerp(nx00, nx10, uy);
        double nxy1 = lerp(nx01, nx11, uy);
        return lerp(nxy0, nxy1, uz);
    }

    private static void applyFacadeGridPlane(List<PlannedBlock> out,
                                             Context ctx,
                                             BlockPos origin,
                                             int bayW,
                                             int bayH,
                                             int mullionT,
                                             int transomT,
                                             int borderT,
                                             int marginU,
                                             int marginY,
                                             BlockState frame,
                                             BlockState fill,
                                             BlockState spandrelFill,
                                             int spandrelEvery,
                                             int spandrelHeight,
                                             int spandrelOffset,
                                             int u0,
                                             int u1,
                                             int y0,
                                             int y1,
                                             int fixed,
                                             boolean uIsX,
                                             int depth,
                                             int inwardSign) {
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
                    put(out, ctx, origin, x, y, z, s);
                }
            }
        }
    }

    private static void applyBandPlane(List<PlannedBlock> out,
                                       Context ctx,
                                       BlockPos origin,
                                       int u0,
                                       int u1,
                                       int y0,
                                       int y1,
                                       int fixed,
                                       boolean uIsX,
                                       int depth,
                                       int inwardSign,
                                       BlockState mat) {
        int au0 = Math.min(u0, u1), au1 = Math.max(u0, u1);
        int ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
        for (int y = ay0; y <= ay1; y++) {
            for (int u = au0; u <= au1; u++) {
                for (int k = 0; k < depth; k++) {
                    int x = uIsX ? u : (fixed + inwardSign * k);
                    int z = uIsX ? (fixed + inwardSign * k) : u;
                    put(out, ctx, origin, x, y, z, mat);
                }
            }
        }
    }

    private static void applyVerticalBandsPlane(List<PlannedBlock> out,
                                                Context ctx,
                                                BlockPos origin,
                                                int u0,
                                                int u1,
                                                int y0,
                                                int y1,
                                                int fixed,
                                                boolean uIsX,
                                                int depth,
                                                int inwardSign,
                                                int step,
                                                int width,
                                                int offset,
                                                BlockState mat) {
        int au0 = Math.min(u0, u1), au1 = Math.max(u0, u1);
        int ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
        for (int y = ay0; y <= ay1; y++) {
            for (int u = au0; u <= au1; u++) {
                int lu = u - au0 - offset;
                if (Math.floorMod(lu, step) >= width) continue;
                for (int k = 0; k < depth; k++) {
                    int x = uIsX ? u : (fixed + inwardSign * k);
                    int z = uIsX ? (fixed + inwardSign * k) : u;
                    put(out, ctx, origin, x, y, z, mat);
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

    private static void carveRectOnFace(List<PlannedBlock> out,
                                        Context ctx,
                                        BlockPos origin,
                                        String face,
                                        int centerX,
                                        int centerZ,
                                        int yBase,
                                        int rectW,
                                        int rectH,
                                        BlockState fill,
                                        BlockState frame,
                                        int frameT,
                                        int mullionStep) {
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
                put(out, ctx, origin, x, y, z, s);
            }
        }
    }

    private static void carveArchOnFace(List<PlannedBlock> out,
                                        Context ctx,
                                        BlockPos origin,
                                        String face,
                                        int centerX,
                                        int centerZ,
                                        int yBase,
                                        int rectW,
                                        int rectH,
                                        String archType,
                                        BlockState fill,
                                        BlockState frame,
                                        int frameT,
                                        int mullionStep,
                                        int archThickness,
                                        BlockState keystone,
                                        String tracery,
                                        BlockState traceryMat,
                                        int traceryThickness,
                                        int traceryYAbs,
                                        int traceryInset,
                                        int foilRadius,
                                        int foilCenterYAbs,
                                        int foilCount,
                                        int foilStepY,
                                        boolean foilStepAuto) {
        int hw = Math.max(1, rectW / 2);
        // split into base + arch cap; default rise tries to feel like an arch
        int archRise = Math.max(2, Math.min(rectH - 1, hw));
        int baseH = Math.max(1, rectH - archRise);
        int y1 = yBase + rectH - 1;

        boolean alongX = "NORTH".equals(face) || "SOUTH".equals(face);

        // Precompute pointed arch radius
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
                        // Intersection of two circles centered at spring points (-hw,0) and (hw,0)
                        double d1 = (du + hw) * (du + hw) + (double) ay * ay;
                        double d2 = (du - hw) * (du - hw) + (double) ay * ay;
                        inside = (d1 <= pr2) && (d2 <= pr2);
                    } else {
                        // Round arch: half circle centered at (0,0) with radius hw, in the cap region
                        inside = (du * du + ay * ay) <= (hw * hw);
                    }
                }
                if (!inside) continue;

                // Decide frame vs fill (with optional mullions)
                boolean isFrame = false;
                if (frameT > 0) {
                    // distance-to-outside <= frameT-1 approximation via neighborhood scan
                    for (int k = 0; k < frameT && !isFrame; k++) {
                        int[] nu = new int[]{du + k, du - k, du, du};
                        int[] ny = new int[]{y + k, y - k, y, y};
                        // quick boundary: any of the 4-neighbors at distance k outside
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
                // Extra arch thickness: enforce border region as frame.
                if (!isFrame && archThickness > 0) {
                    // Simple approximation: treat a slightly expanded border as frame.
                    int duAbs = Math.abs(du);
                    if (duAbs >= hw - (archThickness - 1)) isFrame = true;
                    if ((y - yBase) < archThickness || (y1 - y) < archThickness) isFrame = true;
                }

                BlockState s = (isFrame || isMullion) ? frame : fill;

                // Tracery (inside only): draw decorative bars/shapes using traceryMat.
                if (!isFrame && traceryMat != null && traceryThickness > 0 && tracery != null && !tracery.isBlank()) {
                    int ty = (traceryYAbs != Integer.MIN_VALUE) ? traceryYAbs : (yBase + baseH + (rectH - baseH) / 2);
                    boolean on = false;
                    java.util.List<String> parts = splitTracery(tracery);
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
                                int[][] centers = new int[][]{{cx0 - rr, cy},{cx0 + rr, cy},{cx0, cy - rr},{cx0, cy + rr}};
                                for (int[] c : centers) {
                                    int dx = du - c[0];
                                    int dy = y - c[1];
                                    if (dx * dx + dy * dy <= rr2) { on = true; break; }
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
                                int[][] centers = new int[][]{{cx0 - rr, cy},{cx0 + rr, cy},{cx0, cy + rr}};
                                for (int[] c : centers) {
                                    int dx = du - c[0];
                                    int dy = y - c[1];
                                    if (dx * dx + dy * dy <= rr2) { on = true; break; }
                                }
                            }
                        }
                        if (on) break;
                    }
                    if (on) s = traceryMat;
                }
                // Keystone at apex
                if (keystone != null && du == 0 && y == y1) s = keystone;

                int x = alongX ? (centerX + du) : centerX;
                int z = alongX ? centerZ : (centerZ + du);
                // Optional tracery inset: push decorative tracery blocks one block inward for depth.
                if (traceryInset > 0 && s == traceryMat) {
                    int[] p = insetOnFace(face, x, z, traceryInset);
                    x = p[0]; z = p[1];
                }
                put(out, ctx, origin, x, y, z, s);
            }
        }
    }

    private static java.util.List<String> splitTracery(String tracery) {
        if (tracery == null) return java.util.List.of();
        String t = tracery.trim().toUpperCase(Locale.ROOT);
        if (t.isBlank()) return java.util.List.of();
        // allow CROSS+QUATREFOIL or CROSS,QUATREFOIL or CROSS|QUATREFOIL
        t = t.replace('|', '+').replace(',', '+').replace(' ', '+');
        String[] parts = t.split("\\+");
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
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

    private static int resolveFoilCount(Map<String, Object> op, int winH) {
        // Support:
        // - foilCount / traceryFoilCount as Number
        // - foilCount / traceryFoilCount as "AUTO"
        Object rawA = op == null ? null : op.get("foilCount");
        Object rawB = op == null ? null : op.get("traceryFoilCount");

        if (isAuto(rawA) || isAuto(rawB)) {
            // Heuristic by window height: taller windows get more layers.
            int h = Math.max(0, winH);
            int n = (h >= 11) ? 3 : (h >= 8) ? 2 : 1;
            return clamp(n, 1, 8);
        }

        Integer v = asInt(rawA);
        if (v == null) v = asInt(rawB);
        if (v == null) v = i(op == null ? null : op.get("traceryFoilCount"), i(op == null ? null : op.get("foilCount"), 1));
        return clamp(v, 1, 8);
    }

    private static int resolveFoilCenterY(Map<String, Object> op, int yBase, int winH) {
        // Support:
        // - foilCenterY / traceryFoilCenterY as Number
        // - foilCenterY / traceryFoilCenterY as "AUTO" (per-window)
        Object rawA = op == null ? null : op.get("foilCenterY");
        Object rawB = op == null ? null : op.get("traceryFoilCenterY");

        if (isAuto(rawA) || isAuto(rawB)) {
            // Place the foil cluster in the upper half of the window by default.
            int h = Math.max(0, winH);
            return yBase + Math.max(2, (h * 2) / 3);
        }

        Integer v = asInt(rawA);
        if (v == null) v = asInt(rawB);
        if (v != null) return v;

        return i(op == null ? null : op.get("traceryFoilCenterY"), i(op == null ? null : op.get("foilCenterY"), Integer.MIN_VALUE));
    }

    private static List<Vec3d> parseVecPoints(Object v) {
        return AssemblyBezierOps.parseVecPoints(v);
    }

    /**
     * Catmull-Rom -> cubic Bezier conversion and sampling (server-side copy of PathTool logic).
     */
    private static List<Vec3d> sampleBezierSpline(List<Vec3d> waypoints, int samplesPerBlock) {
        return AssemblyBezierOps.sampleBezierSpline(waypoints, samplesPerBlock);
    }

    private static Vec3d bezier(Vec3d p0, Vec3d c1, Vec3d c2, Vec3d p3, double t) {
        return AssemblyBezierOps.bezier(p0, c1, c2, p3, t);
    }

    private static double lerp(double a, double b, double t) {
        return AssemblyBezierOps.lerp(a, b, t);
    }

    private static long packXYZ(int x, int y, int z) {
        return AssemblySeamMathOps.packXYZ(x, y, z);
    }

    private static long packUV(int u, int v) {
        return AssemblySeamMathOps.packUV(u, v);
    }

    private static void connectToLast(List<PlannedBlock> out,
                                      Context ctx,
                                      BlockPos origin,
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
                    long key = packXYZ(xi, yi, zi);
                    if (seen != null && !seen.add(key)) continue;
                    put(out, ctx, origin, xi, yi, zi, s);
                }
            }
        }

        lastSection.put(uvKey, new long[]{x, y, z});
    }

    private static int snap(double v, String mode) {
        String m = (mode == null) ? "ROUND" : mode.trim().toUpperCase(Locale.ROOT);
        return switch (m) {
            case "FLOOR" -> (int) Math.floor(v);
            case "CEIL" -> (int) Math.ceil(v);
            default -> (int) Math.round(v);
        };
    }

    // ----- 2D polygon helpers for profile=POLYGON -----
    private static List<List<int[]>> parseProfileRings(Map<String, Object> op) {
        return AssemblyProfilePolygonOps.parseProfileRings(op);
    }

    private static List<int[]> parseProfile2D(Object v) {
        return AssemblyProfilePolygonOps.parseProfile2D(v);
    }

    private static int[] bounds2D(List<int[]> pts) {
        return AssemblyProfilePolygonOps.bounds2D(pts);
    }

    private static int[] boundsRings2D(List<List<int[]>> rings) {
        return AssemblyProfilePolygonOps.boundsRings2D(rings);
    }

    private static List<int[]> scalePoly(List<int[]> pts, double s) {
        return AssemblyProfilePolygonOps.scalePoly(pts, s);
    }

    private static List<List<int[]>> scaleRings(List<List<int[]>> rings, double s) {
        return AssemblyProfilePolygonOps.scaleRings(rings, s);
    }

    private static boolean pointInPoly2D(int u, int v, List<int[]> poly) {
        return AssemblyProfilePolygonOps.pointInPoly2D(u, v, poly);
    }

    private static boolean pointInRings2D(int u, int v, List<List<int[]>> rings) {
        return AssemblyProfilePolygonOps.pointInRings2D(u, v, rings);
    }

    private static boolean isRingsBorder(int u, int v, List<List<int[]>> rings, int t) {
        return AssemblyProfilePolygonOps.isRingsBorder(u, v, rings, t);
    }

    private static boolean isAuto(Object v) {
        return AssemblyValueParser.isAuto(v);
    }

    private static Integer asInt(Object v) {
        return AssemblyValueParser.asInt(v);
    }

    private static void carveRoseOnFace(List<PlannedBlock> out,
                                        Context ctx,
                                        BlockPos origin,
                                        String face,
                                        int centerX,
                                        int centerZ,
                                        int centerY,
                                        int r,
                                        int ring,
                                        int petals,
                                        double phase,
                                        int spokeWidth,
                                        double spokeThreshold,
                                        BlockState fill,
                                        BlockState innerFill,
                                        BlockState frame,
                                        BlockState spokeMat) {
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

                // Spokes/petals: approximate by angle bins; draw a thin line at each bin boundary.
                boolean isSpoke = false;
                if (petals > 0 && d2 > 1) {
                    double ang = Math.atan2(dy, du) + phase; // -pi..pi + phase
                    if (ang < 0) ang += Math.PI * 2.0;
                    if (ang >= Math.PI * 2.0) ang -= Math.PI * 2.0;
                    double bin = (ang / (Math.PI * 2.0)) * petals;
                    double frac = bin - Math.floor(bin);
                    if (frac < spokeThreshold || frac > (1.0 - spokeThreshold)) isSpoke = true;
                    // thicken radial boundaries
                    if (spokeWidth > 1) {
                        double t = Math.min(frac, 1.0 - frac);
                        if (t < (spokeThreshold * spokeWidth)) isSpoke = true;
                    }
                }

                BlockState s = isRing ? frame : (isSpoke ? spokeMat : (isCore ? innerFill : fill));
                int x = alongX ? (centerX + du) : centerX;
                int z = alongX ? centerZ : (centerZ + du);
                int y = centerY + dy;
                put(out, ctx, origin, x, y, z, s);
            }
        }
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
    
    private static int[] parsePoint(Object v) {
        return AssemblyRasterOps.parsePoint(v);
    }

    private static List<BlockPos> rasterizeLine2D(BlockPos a, BlockPos b, ServerWorld world, boolean followTerrain, int maxStep) {
        return AssemblyRasterOps.rasterizeLine2D(a, b, world, followTerrain, maxStep);
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

    private static double[] bezierBasis3(double t) {
        return AssemblyBezierSurfaceOps.bezierBasis3(t);
    }

    private static int[][][] sampleBezierSurface(List<int[]> ctrl, int uN, int vN) {
        return AssemblyBezierSurfaceOps.sampleBezierSurface(ctrl, uN, vN);
    }

    private static void connectSurfaceGrid(List<PlannedBlock> out, Context ctx, BlockPos origin,
                                           int[][][] grid, int uN, int vN, int thick, BlockState mat) {
        AssemblyVoxelBridgeOps.connectSurfaceGrid(
                (x, y, z, state) -> put(out, ctx, origin, x, y, z, state),
                grid, uN, vN, thick, mat
        );
    }

    private static List<int[]> readBezierControlPoints(Object ptsObj) {
        return AssemblyBezierSurfaceOps.readBezierControlPoints(ptsObj);
    }

    private enum Edge { U0, U1, V0, V1 }

    private static final class PatchData {
        @SuppressWarnings("unused")
        final String id;
        final int uN, vN;
        final int thick;
        @SuppressWarnings("unused")
        final BlockState mat;
        final int[][][] grid; // [u][v][xyz]
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
        // allow numeric index
        try {
            int idx = Integer.parseInt(k);
            if (idx >= 0 && idx < patches.size()) return patches.get(idx);
        } catch (Exception ignored) {}
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

    private static long dist2(int[] a, int[] b) {
        return AssemblySeamMathOps.dist2(a, b);
    }

    private static String edgeSignature(PatchData p, Edge e, boolean reverse) {
        int n = edgeCount(p, e);
        return AssemblySeamMathOps.edgeSignature(n, reverse, i -> edgePoint(p, e, i));
    }

    private static void stitchEdge(List<PlannedBlock> out, Context ctx, BlockPos origin,
                                   PatchData a, Edge ea, PatchData b, Edge eb,
                                   int thick, BlockState mat,
                                   int capWidth, BlockState capMat) {
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
                        placeBeamLine(out, ctx, origin, x0, y0, z0, x1, y1, z1, thickness, beamH, state),
                (cx, cy, cz, thickness, h, state) ->
                        placePrism(out, ctx, origin, cx, cy, cz, thickness, h, state)
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

    private static void stitchEdgeResampled(List<PlannedBlock> out, Context ctx, BlockPos origin,
                                           PatchData a, Edge ea, PatchData b, Edge eb, boolean reverse,
                                           int thick, BlockState mat, int stitchSamples, boolean resample,
                                           int capWidth, BlockState capMat) {
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
                        placeBeamLine(out, ctx, origin, x0, y0, z0, x1, y1, z1, thickness, beamH, state),
                (cx, cy, cz, thickness, h, state) ->
                        placePrism(out, ctx, origin, cx, cy, cz, thickness, h, state)
        );
    }

    private static void stitchEdgeResampledRange(List<PlannedBlock> out, Context ctx, BlockPos origin,
                                                 PatchData a, Edge ea, double a0, double a1,
                                                 PatchData b, Edge eb, double b0, double b1,
                                                 boolean reverse,
                                                 int thick, BlockState mat, int stitchSamples, boolean resample,
                                                 int capWidth, BlockState capMat) {
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
                        placeBeamLine(out, ctx, origin, x0, y0, z0, x1, y1, z1, thickness, beamH, state),
                (cx, cy, cz, thickness, h, state) ->
                        placePrism(out, ctx, origin, cx, cy, cz, thickness, h, state)
        );
    }

    private static Edge parseEdge(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        // allow synonyms: LEFT/RIGHT/TOP/BOTTOM
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
        } catch (Exception ignored) {}
        return null;
    }

    private static void surfaceOffsetFromGrid(List<PlannedBlock> out,
                                              Context ctx,
                                              BlockPos origin,
                                              int[][][] grid,
                                              int uN,
                                              int vN,
                                              int offset,
                                              int shellT,
                                              String mode,
                                              String normalMode,
                                              double stepLen,
                                              boolean dedupe,
                                              boolean connect,
                                              int connectMaxStep,
                                              BlockState mat) {
        if (grid == null) return;
        boolean outSide = mode.isBlank() || mode.equals("BOTH") || mode.equals("OUT") || mode.equals("OUTWARD");
        boolean inSide = mode.equals("BOTH") || mode.equals("IN") || mode.equals("INWARD");
        String nm = (normalMode == null) ? "DDA" : normalMode.trim().toUpperCase(Locale.ROOT);
        double st = (stepLen <= 0) ? 1.0 : stepLen;

        for (int iu = 0; iu <= uN; iu++) {
            for (int iv = 0; iv <= vN; iv++) {
                int[] p = grid[iu][iv];
                if (p == null) continue;
                // Difference tangents (clamped)
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

                // normal = du x dv
                long nx = (long) duy * dvz - (long) duz * dvy;
                long ny = (long) duz * dvx - (long) dux * dvz;
                long nz = (long) dux * dvy - (long) duy * dvx;
                if (nx == 0 && ny == 0 && nz == 0) continue;

                if (nm.equals("AXIS")) {
                    // Legacy: choose dominant axis as voxel normal direction
                    int ax = (int) Math.signum(nx);
                    int ay = (int) Math.signum(ny);
                    int az = (int) Math.signum(nz);
                    long anx = Math.abs(nx), any = Math.abs(ny), anz = Math.abs(nz);
                    int dx = 0, dy = 0, dz = 0;
                    if (anx >= any && anx >= anz) { dx = ax; dy = 0; dz = 0; }
                    else if (any >= anz) { dx = 0; dy = ay; dz = 0; }
                    else { dx = 0; dy = 0; dz = az; }
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    if (outSide) {
                        int base = offset;
                        for (int t = 0; t < shellT; t++) {
                            int k = base + t;
                            put(out, ctx, origin, p[0] + dx * k, p[1] + dy * k, p[2] + dz * k, mat);
                        }
                    }
                    if (inSide) {
                        int base = -offset;
                        for (int t = 0; t < shellT; t++) {
                            int k = base + t;
                            put(out, ctx, origin, p[0] - dx * k, p[1] - dy * k, p[2] - dz * k, mat);
                        }
                    }
                } else {
                    // DDA: continuous unit normal, multi-step discrete walk (rounding each step)
                    double len = Math.sqrt((double) nx * nx + (double) ny * ny + (double) nz * nz);
                    if (len < 1e-6) continue;
                    double ux = nx / len;
                    double uy = ny / len;
                    double uz = nz / len;

                    if (outSide) {
                        ddaWalkPut(out, ctx, origin, p[0], p[1], p[2], ux, uy, uz, offset, shellT, st, dedupe, connect, connectMaxStep, mat);
                    }
                    if (inSide) {
                        ddaWalkPut(out, ctx, origin, p[0], p[1], p[2], -ux, -uy, -uz, offset, shellT, st, dedupe, connect, connectMaxStep, mat);
                    }
                }
            }
        }
    }

    private static void ddaWalkPut(List<PlannedBlock> out,
                                   Context ctx,
                                   BlockPos origin,
                                   int x0, int y0, int z0,
                                   double ux, double uy, double uz,
                                   int offset,
                                   int shellT,
                                   double stepLen,
                                   boolean dedupe,
                                   boolean connect,
                                   int connectMaxStep,
                                   BlockState mat) {
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
                        drawVoxelLine(out, ctx, origin, lastX, lastY, lastZ, xi, yi, zi, mat);
                    } else {
                        put(out, ctx, origin, xi, yi, zi, mat);
                    }
                } else {
                    put(out, ctx, origin, xi, yi, zi, mat);
                }
                lastX = xi; lastY = yi; lastZ = zi;
            }

            fx += ux * stepLen;
            fy += uy * stepLen;
            fz += uz * stepLen;
        }
    }

    private static void drawVoxelLine(List<PlannedBlock> out,
                                      Context ctx,
                                      BlockPos origin,
                                      int x0, int y0, int z0,
                                      int x1, int y1, int z1,
                                      BlockState mat) {
        int dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (steps <= 0) {
            put(out, ctx, origin, x0, y0, z0, mat);
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
                put(out, ctx, origin, xi, yi, zi, mat);
                lastX = xi; lastY = yi; lastZ = zi;
            }
            fx += sx;
            fy += sy;
            fz += sz;
        }
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
                lerp(p0[0], p1[0], t),
                lerp(p0[1], p1[1], t),
                lerp(p0[2], p1[2], t)
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

    private static final class LoftSection {
        final int x, y, z;
        final List<int[]> profile; // 2D points (x,y) relative to section origin; z assumed 0 in P0
        LoftSection(int x, int y, int z, List<int[]> profile) {
            this.x = x; this.y = y; this.z = z; this.profile = profile;
        }
    }

    private static List<int[]> read2DProfilePoints(Object profObj) {
        // Accept:
        // - profilePoints: [{x,y}...]
        // - profileRings/rings: [ [{x,y}..] , ... ] => use first ring
        if (profObj == null) return null;
        if (!(profObj instanceof List<?> list)) return null;
        if (!list.isEmpty() && list.getFirst() instanceof List<?>) {
            // rings form
            Object ring0 = list.getFirst();
            if (!(ring0 instanceof List<?> ring)) return null;
            List<int[]> out = new ArrayList<>();
            for (Object p : ring) {
                if (p instanceof Map<?, ?> pm) {
                    out.add(new int[]{i(pm.get("x"), 0), i(pm.get("y"), 0)});
                }
            }
            return out.isEmpty() ? null : out;
        } else {
            List<int[]> out = new ArrayList<>();
            for (Object p : list) {
                if (p instanceof Map<?, ?> pm) {
                    out.add(new int[]{i(pm.get("x"), 0), i(pm.get("y"), 0)});
                }
            }
            return out.isEmpty() ? null : out;
        }
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
    private static boolean pointInPoly(int x, int z, List<int[]> poly) {
        return AssemblyProfilePolygonOps.pointInPolyXZ(x, z, poly);
    }

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


