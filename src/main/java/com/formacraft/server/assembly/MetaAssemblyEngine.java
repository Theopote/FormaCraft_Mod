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
                        List<int[]> poly2 = parseProfile2D(op.get("profilePoints"));
                        if (poly2.size() < 3) break;
                        double s0 = d(op.get("profileScale0"), d(op.get("scale0"), 1.0));
                        double s1 = d(op.get("profileScale1"), d(op.get("scale1"), 1.0));
                        double sc = lerp(s0, s1, tt);
                        if (sc <= 0.05) sc = 0.05;
                        // bounds in profile space
                        int[] bb = bounds2D(poly2);
                        int uMin = (int) Math.floor(bb[0] * sc);
                        int uMax = (int) Math.ceil(bb[1] * sc);
                        int vMin = (int) Math.floor(bb[2] * sc);
                        int vMax = (int) Math.ceil(bb[3] * sc);
                        // safety cap
                        int area2d = (uMax - uMin + 1) * (vMax - vMin + 1);
                        if (area2d > 20000) break;

                        // scaled polygon for point tests
                        List<int[]> sp = scalePoly(poly2, sc);

                        for (int uu = uMin; uu <= uMax; uu++) {
                            for (int vv = vMin; vv <= vMax; vv++) {
                                boolean inside = pointInPoly2D(uu, vv, sp);
                                if (!inside) continue;
                                boolean border = true;
                                if (hollow) {
                                    border = isPolyBorder(uu, vv, sp, t);
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
                                    boolean inside = pointInPoly2D(uu, vv, sp);
                                    if (!inside) continue;
                                    boolean border = isPolyBorder(uu, vv, sp, capThickness);
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
                // - pattern: GRID / STRIPES_V / STRIPES_H / RIBS_V / RIBS_H
                // - step: spacing
                // - thickness: rib thickness
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

                if ("NORTH".equals(face)) {
                    applyPatternPlane(out, ctx, curOrigin, pattern, step, thick, accent, ax0, ax1, ay0, ay1, az0, true);
                } else if ("SOUTH".equals(face)) {
                    applyPatternPlane(out, ctx, curOrigin, pattern, step, thick, accent, ax0, ax1, ay0, ay1, az1, true);
                } else if ("WEST".equals(face)) {
                    applyPatternPlane(out, ctx, curOrigin, pattern, step, thick, accent, az0, az1, ay0, ay1, ax0, false);
                } else if ("EAST".equals(face)) {
                    applyPatternPlane(out, ctx, curOrigin, pattern, step, thick, accent, az0, az1, ay0, ay1, ax1, false);
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
                switch (pattern) {
                    case "STRIPES_V", "STRIPES_VERTICAL" -> place = (Math.floorMod(u, step) == 0);
                    case "STRIPES_H", "STRIPES_HORIZONTAL" -> place = (Math.floorMod(y, step) == 0);
                    case "RIBS_V", "RIBS_VERTICAL" -> place = (Math.floorMod(u, step) < thick);
                    case "RIBS_H", "RIBS_HORIZONTAL" -> place = (Math.floorMod(y, step) < thick);
                    default -> place = (Math.floorMod(u, step) == 0) || (Math.floorMod(y, step) == 0);
                }
                if (!place) continue;
                int x = uIsX ? u : fixed;
                int z = uIsX ? fixed : u;
                put(out, ctx, origin, x, y, z, accent);
            }
        }
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
        java.util.ArrayList<Vec3d> out = new java.util.ArrayList<>();
        if (!(v instanceof List<?> list)) return out;
        for (Object p : list) {
            if (!(p instanceof Map<?, ?> pm)) continue;
            double x = d(pm.get("x"), 0.0);
            double y = d(pm.get("y"), 0.0);
            double z = d(pm.get("z"), 0.0);
            out.add(new Vec3d(x, y, z));
        }
        return out;
    }

    /**
     * Catmull-Rom -> cubic Bezier conversion and sampling (server-side copy of PathTool logic).
     */
    private static List<Vec3d> sampleBezierSpline(List<Vec3d> waypoints, int samplesPerBlock) {
        if (waypoints == null || waypoints.size() < 2) return java.util.Collections.emptyList();
        int n = waypoints.size();
        java.util.ArrayList<Vec3d> out = new java.util.ArrayList<>();
        for (int i = 0; i < n - 1; i++) {
            Vec3d p0 = (i == 0) ? waypoints.getFirst() : waypoints.get(i - 1);
            Vec3d p1 = waypoints.get(i);
            Vec3d p2 = waypoints.get(i + 1);
            Vec3d p3 = (i + 2 < n) ? waypoints.get(i + 2) : waypoints.get(n - 1);
            if (p0 == null || p1 == null || p2 == null || p3 == null) continue;

            Vec3d c1 = p1.add(p2.subtract(p0).multiply(1.0 / 6.0));
            Vec3d c2 = p2.subtract(p3.subtract(p1).multiply(1.0 / 6.0));

            double segLen = p1.distanceTo(p2);
            int steps = (int) Math.max(6, Math.ceil(segLen * Math.max(2.0, samplesPerBlock)));
            int start = (i == 0) ? 0 : 1; // avoid duplicates
            for (int s = start; s <= steps; s++) {
                double t = s / (double) steps;
                out.add(bezier(p1, c1, c2, p2, t));
            }
        }
        return out;
    }

    private static Vec3d bezier(Vec3d p0, Vec3d c1, Vec3d c2, Vec3d p3, double t) {
        double u = 1.0 - t;
        double tt = t * t;
        double uu = u * u;
        double uuu = uu * u;
        double ttt = tt * t;
        double x = uuu * p0.x + 3.0 * uu * t * c1.x + 3.0 * u * tt * c2.x + ttt * p3.x;
        double y = uuu * p0.y + 3.0 * uu * t * c1.y + 3.0 * u * tt * c2.y + ttt * p3.y;
        double z = uuu * p0.z + 3.0 * uu * t * c1.z + 3.0 * u * tt * c2.z + ttt * p3.z;
        return new Vec3d(x, y, z);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static long packXYZ(int x, int y, int z) {
        // pack 3 signed ints into one long (range-limited; good enough for local coords)
        long xx = (x & 0x1FFFFF); // 21 bits
        long yy = (y & 0x3FFFF);  // 18 bits
        long zz = (z & 0x1FFFFF); // 21 bits
        return (xx << 42) | (yy << 24) | zz;
    }

    private static long packUV(int u, int v) {
        return (((long) u) << 32) ^ (v & 0xffffffffL);
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
    private static List<int[]> parseProfile2D(Object v) {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        if (!(v instanceof List<?> list)) return out;
        for (Object p : list) {
            if (!(p instanceof Map<?, ?> pm)) continue;
            int u = i(pm.get("u"), i(pm.get("x"), 0));
            int vv = i(pm.get("v"), i(pm.get("y"), 0));
            out.add(new int[]{u, vv});
        }
        return out;
    }

    private static int[] bounds2D(List<int[]> pts) {
        int uMin = Integer.MAX_VALUE, uMax = Integer.MIN_VALUE;
        int vMin = Integer.MAX_VALUE, vMax = Integer.MIN_VALUE;
        for (int[] p : pts) {
            if (p == null || p.length < 2) continue;
            uMin = Math.min(uMin, p[0]); uMax = Math.max(uMax, p[0]);
            vMin = Math.min(vMin, p[1]); vMax = Math.max(vMax, p[1]);
        }
        if (uMin == Integer.MAX_VALUE) return new int[]{0,0,0,0};
        return new int[]{uMin, uMax, vMin, vMax};
    }

    private static List<int[]> scalePoly(List<int[]> pts, double s) {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        for (int[] p : pts) {
            out.add(new int[]{(int) Math.round(p[0] * s), (int) Math.round(p[1] * s)});
        }
        return out;
    }

    private static boolean pointInPoly2D(int u, int v, List<int[]> poly) {
        // even-odd rule
        boolean inside = false;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            int[] pi = poly.get(i);
            int[] pj = poly.get(j);
            int xi = pi[0], yi = pi[1];
            int xj = pj[0], yj = pj[1];
            boolean intersect = ((yi > v) != (yj > v)) && (u < (double) (xj - xi) * (v - yi) / (yj - yi + 0.000001) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    private static boolean isPolyBorder(int u, int v, List<int[]> poly, int t) {
        // A simple border test: if any neighbor within manhattan distance t is outside the polygon -> border.
        for (int k = 1; k <= t; k++) {
            if (!pointInPoly2D(u + k, v, poly)) return true;
            if (!pointInPoly2D(u - k, v, poly)) return true;
            if (!pointInPoly2D(u, v + k, poly)) return true;
            if (!pointInPoly2D(u, v - k, poly)) return true;
        }
        return false;
    }

    private static boolean isAuto(Object v) {
        if (v == null) return false;
        String s = String.valueOf(v).trim().toUpperCase(Locale.ROOT);
        return s.equals("AUTO") || s.equals("A") || s.equals("AUTOMATIC");
    }

    private static Integer asInt(Object v) {
        try {
            if (v instanceof Number n) return n.intValue();
            if (v == null) return null;
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return null;
            // Don't parse non-numeric like "AUTO"
            if (!s.matches("[-+]?\\d+")) return null;
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return null;
        }
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

    private static int[] parsePoint(Object v) {
        if (v instanceof Map<?, ?> m) {
            return new int[]{i(m.get("x"), 0), i(m.get("y"), 0), i(m.get("z"), 0)};
        }
        return new int[]{0, 0, 0};
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

    private static double d(Object v, double def) {
        try {
            if (v instanceof Number n) return n.doubleValue();
            if (v != null) return Double.parseDouble(String.valueOf(v).trim());
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


