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
 *
 * This is intentionally conservative: opt-in only (extra.assembly).
 */
public final class MetaAssemblyEngine {
    public record Context(ServerWorld world,
                          BlockPos origin,
                          Direction entranceFacing,
                          String paletteId) {}

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
            case "SHELL_BOX" -> {
                // box shell with semantic materials
                int w = clamp(i(op.get("w"), 15), 5, 129);
                int d = clamp(i(op.get("d"), 15), 5, 129);
                int h = clamp(i(op.get("h"), 18), 6, 255);
                int floorStep = clamp(i(op.get("floorStep"), 4), 3, 8);

                BlockState wall = pick(ctx, op, "wall", "WALL_BASE", 0xA55001L, Blocks.STONE_BRICKS.getDefaultState());
                BlockState glass = pick(ctx, op, "window", "WINDOW", 0xA55002L, Blocks.GLASS_PANE.getDefaultState());
                BlockState floor = pick(ctx, op, "floor", "FLOORING", 0xA55003L, Blocks.SMOOTH_STONE.getDefaultState());
                BlockState roof = pick(ctx, op, "roof", "FLOOR_SLAB", 0xA55004L, Blocks.SMOOTH_STONE_SLAB.getDefaultState());

                // local coords: centered around origin (like OfficeBlock)
                int halfW = w / 2;
                int halfD = d / 2;

                // shell walls
                for (int yy = 0; yy <= h; yy++) {
                    boolean windowBand = (yy % 4 == 2) && yy <= h - 2;
                    for (int x = -halfW; x <= halfW; x++) {
                        for (int z = -halfD; z <= halfD; z++) {
                            boolean edge = (Math.abs(x) == halfW) || (Math.abs(z) == halfD);
                            if (!edge) continue;
                            BlockState s = wall;
                            if (windowBand && (Math.abs(x) != halfW || Math.abs(z) != halfD)) s = glass;
                            put(out, ctx, curOrigin, x, yy, z, s);
                        }
                    }
                }

                // floors
                for (int yy = 0; yy <= h; yy += floorStep) {
                    for (int x = -halfW + 1; x <= halfW - 1; x++) {
                        for (int z = -halfD + 1; z <= halfD - 1; z++) {
                            put(out, ctx, curOrigin, x, yy, z, floor);
                        }
                    }
                }

                // roof cap (best-effort)
                for (int x = -halfW; x <= halfW; x++) {
                    for (int z = -halfD; z <= halfD; z++) {
                        put(out, ctx, curOrigin, x, h + 1, z, roof);
                    }
                }

                // hollow interior
                fillBox(out, ctx, curOrigin, -halfW + 1, 1, -halfD + 1, halfW - 1, h, halfD - 1, Blocks.AIR.getDefaultState());
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
                int x0 = 0, y0 = 0, z0 = 0, x1 = 0, y1 = 0, z1 = 0;
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
            case "PATH_ROUTE" -> {
                // Reuse PathGenerator (supports DRAPE etc via terrainAdaptation in extra).
                int width = clamp(i(op.get("width"), i(op.get("thickness"), 3)), 1, 15);
                String style = str(op.get("style"), "default");

                int[] p0 = parsePoint(op.get("from"), 0, 0, 0);
                int[] p1 = parsePoint(op.get("to"), 0, 0, 0);
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
                int[] p0 = parsePoint(op.get("from"), 0, 0, 0);
                int[] p1 = parsePoint(op.get("to"), 0, 0, 0);
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
                int[] p0 = parsePoint(op.get("from"), 0, 0, 0);
                int[] p1 = parsePoint(op.get("to"), 0, 0, 0);
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
                    // slope along Z
                    for (int step = 0; step < rise; step++) {
                        int y = yBase + step;
                        int zz0 = z0 + step;
                        int zz1 = z1 - step;
                        if (zz0 > zz1) break;
                        Direction faceNorth = Direction.NORTH; // local facing
                        Direction faceSouth = Direction.SOUTH;
                        BlockState sN = roof.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING) ? roof.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, faceNorth) : roof;
                        BlockState sS = roof.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING) ? roof.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, faceSouth) : roof;
                        for (int x = x0; x <= x1; x++) {
                            put(out, ctx, curOrigin, x, y, zz0, sS); // south-facing stair on north slope edge
                            put(out, ctx, curOrigin, x, y, zz1, sN); // north-facing stair on south slope edge
                        }
                    }
                } else {
                    // slope along X
                    for (int step = 0; step < rise; step++) {
                        int y = yBase + step;
                        int xx0 = x0 + step;
                        int xx1 = x1 - step;
                        if (xx0 > xx1) break;
                        Direction faceWest = Direction.WEST;
                        Direction faceEast = Direction.EAST;
                        BlockState sW = roof.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING) ? roof.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, faceWest) : roof;
                        BlockState sE = roof.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING) ? roof.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, faceEast) : roof;
                        for (int z = z0; z <= z1; z++) {
                            put(out, ctx, curOrigin, xx0, y, z, sE);
                            put(out, ctx, curOrigin, xx1, y, z, sW);
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
                BlockState roomWall = coreWall;
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

    private static BlockState pick(Context ctx, Map<String, Object> op, String overrideKey, String semanticKey, long salt, BlockState fallback) {
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

    private static int[] parsePoint(Object v, int dx, int dy, int dz) {
        if (v instanceof Map<?, ?> m) {
            return new int[]{i(m.get("x"), dx), i(m.get("y"), dy), i(m.get("z"), dz)};
        }
        return new int[]{dx, dy, dz};
    }

    private static List<BlockPos> rasterizeLine2D(BlockPos a, BlockPos b, ServerWorld world, boolean followTerrain, int maxStep) {
        // Bresenham in XZ; Y is taken from a/b (or terrain if followTerrain)
        int x0 = a.getX(), z0 = a.getZ();
        int x1 = b.getX(), z1 = b.getZ();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;

        List<BlockPos> pts = new ArrayList<>(Math.max(dx, dz) + 1);
        List<Integer> ground = new ArrayList<>();

        int x = x0, z = z0;
        while (true) {
            int y = a.getY();
            int gy = y;
            if (followTerrain && world != null) {
                int top = world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, x, z);
                gy = top - 1;
                y = Math.max(y, gy);
            }
            pts.add(new BlockPos(x, y, z));
            ground.add(gy);
            if (x == x1 && z == z1) break;
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; x += sx; }
            if (e2 < dx) { err += dx; z += sz; }
        }

        if (followTerrain && maxStep > 0 && pts.size() > 2) {
            int n = pts.size();
            int[] ys = new int[n];
            for (int i = 0; i < n; i++) ys[i] = pts.get(i).getY();
            for (int it = 0; it < 3; it++) {
                for (int i = 1; i < n; i++) {
                    int prev = ys[i - 1];
                    int cur = ys[i];
                    if (cur > prev + maxStep) cur = prev + maxStep;
                    if (cur < prev - maxStep) cur = prev - maxStep;
                    if (cur < ground.get(i)) cur = ground.get(i);
                    ys[i] = cur;
                }
                for (int i = n - 2; i >= 0; i--) {
                    int next = ys[i + 1];
                    int cur = ys[i];
                    if (cur > next + maxStep) cur = next + maxStep;
                    if (cur < next - maxStep) cur = next - maxStep;
                    if (cur < ground.get(i)) cur = ground.get(i);
                    ys[i] = cur;
                }
            }
            for (int i = 0; i < n; i++) {
                BlockPos p = pts.get(i);
                pts.set(i, new BlockPos(p.getX(), ys[i], p.getZ()));
            }
        }
        return pts;
    }

    private static void placePrism(List<PlannedBlock> out, Context ctx, BlockPos origin, int cx, int cy, int cz, int thickness, int h, BlockState s) {
        int half = thickness / 2;
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                for (int y = 0; y < h; y++) {
                    put(out, ctx, origin, cx + x, cy + y, cz + z, s);
                }
            }
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
        boolean inside = false;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            int xi = poly.get(i)[0], zi = poly.get(i)[1];
            int xj = poly.get(j)[0], zj = poly.get(j)[1];
            boolean intersect = ((zi > z) != (zj > z)) && (x < (long) (xj - xi) * (z - zi) / (long) (zj - zi) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    private static int i(Object v, int def) {
        try {
            if (v instanceof Number n) return n.intValue();
            if (v != null) return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception ignored) {}
        return def;
    }

    private static boolean bool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return def;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    private static String str(Object v, String def) {
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}


