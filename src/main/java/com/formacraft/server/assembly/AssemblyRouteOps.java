package com.formacraft.server.assembly;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.common.model.path.PathSpec;
import com.formacraft.common.skeleton.linear.LinearPathPlan;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.common.generation.structure.BridgeGenerator;
import com.formacraft.common.generation.structure.path.PathGenerator;
import com.formacraft.server.skeleton.linear.LinearWallInterpreter;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Route/path/bridge assembly ops extracted from {@link MetaAssemblyEngine}.
 */
public final class AssemblyRouteOps {
    private AssemblyRouteOps() {}

    public interface Adapter {
        BlockState pick(MetaAssemblyEngine.Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback);
        int i(Object v, int def);
        boolean bool(Object v, boolean def);
        int clamp(int v, int min, int max);
    }

    public static void applyPathRoute(List<PlannedBlock> out,
                                      MetaAssemblyEngine.Context ctx,
                                      BlockPos origin,
                                      Map<String, Object> op,
                                      Adapter adapter) {
        int width = adapter.clamp(adapter.i(op.get("width"), adapter.i(op.get("thickness"), 3)), 1, 15);
        String style = String.valueOf(op.getOrDefault("style", "default"));

        int[] p0 = AssemblyRasterOps.parsePoint(op.get("from"));
        int[] p1 = AssemblyRasterOps.parsePoint(op.get("to"));
        PathSpec ps = new PathSpec();
        ps.setFrom(new PathSpec.Point(p0[0], p0[1], p0[2]));
        ps.setTo(new PathSpec.Point(p1[0], p1[1], p1[2]));
        ps.setWidth(width);
        ps.setStyle(style);

        java.util.Map<String, Object> extra = new java.util.HashMap<>();
        if (ctx.paletteId() != null && !ctx.paletteId().isBlank()) extra.put("paletteId", ctx.paletteId());
        if (op.get("terrainAdaptation") != null) extra.put("terrainAdaptation", op.get("terrainAdaptation"));
        ps.setExtra(extra);

        GeneratedStructure gs = new PathGenerator().generate(ps, origin, ctx.world());
        out.addAll(gs.getBlocks());
    }

    public static void applyWallRoute(List<PlannedBlock> out,
                                      MetaAssemblyEngine.Context ctx,
                                      BlockPos origin,
                                      Map<String, Object> op,
                                      Adapter adapter) {
        int[] p0 = AssemblyRasterOps.parsePoint(op.get("from"));
        int[] p1 = AssemblyRasterOps.parsePoint(op.get("to"));
        int height = adapter.clamp(adapter.i(op.get("wallHeight"), adapter.i(op.get("height"), 10)), 4, 120);
        int thickness = adapter.clamp(adapter.i(op.get("wallThickness"), adapter.i(op.get("thickness"), 5)), 3, 31);
        int towerSpacing = adapter.clamp(adapter.i(op.get("towerSpacing"), 48), 8, 512);
        int foundationDepth = adapter.clamp(adapter.i(op.get("foundationDepth"), 3), 0, 16);
        int maxStep = adapter.clamp(adapter.i(op.get("maxStep"), 1), 0, 8);
        boolean followTerrain = adapter.bool(op.get("followTerrain"), true);
        boolean crenels = adapter.bool(op.get("crenels"), true);

        Object ta = op.get("terrainAdaptation");
        if (ta instanceof Map<?, ?> tm) {
            String mode = String.valueOf(tm.get("mode") == null ? "" : tm.get("mode")).trim().toUpperCase(Locale.ROOT);
            if (mode.contains("DRAPE")) {
                followTerrain = true;
                if (!op.containsKey("maxStep") && tm.get("max_step_height") != null) {
                    maxStep = adapter.clamp(adapter.i(tm.get("max_step_height"), maxStep), 0, 8);
                }
                if (!op.containsKey("foundationDepth") && tm.get("foundation_depth") != null) {
                    foundationDepth = adapter.clamp(adapter.i(tm.get("foundation_depth"), foundationDepth), 0, 16);
                }
            }
        }

        BlockState wall = adapter.pick(ctx, op, "wall", "WALL_BASE", 0xA56001L, Blocks.STONE_BRICKS.getDefaultState());
        BlockState accent = adapter.pick(ctx, op, "accent", "DECOR_DETAIL", 0xA56002L, Blocks.MOSSY_STONE_BRICKS.getDefaultState());
        BlockState walkway = adapter.pick(ctx, op, "walkway", "FLOORING", 0xA56003L, wall);
        BlockState walkwayStairs = adapter.pick(ctx, op, "walkwayStairs", "STAIRS", 0xA56004L, Blocks.STONE_BRICK_STAIRS.getDefaultState());
        BlockState crenel = adapter.pick(ctx, op, "crenel", "DECOR_DETAIL", 0xA56005L, Blocks.STONE_BRICK_WALL.getDefaultState());
        BlockState tower = adapter.pick(ctx, op, "tower", "WALL_BASE", 0xA56006L, wall);
        BlockState foundation = adapter.pick(ctx, op, "foundation", "WALL_FOUNDATION", 0xA56007L, wall);

        BlockPos a = origin.add(p0[0], p0[1], p0[2]);
        BlockPos b = origin.add(p1[0], p1[1], p1[2]);
        List<BlockPos> pts = AssemblyRasterOps.rasterizeLine2D(a, b, ctx.world(), followTerrain, maxStep);
        LinearPathPlan plan = new LinearPathPlan(pts, thickness, height, towerSpacing, crenels);
        List<PlannedBlock> wblocks = new LinearWallInterpreter(
                wall, accent, false, walkway, walkwayStairs, crenel, tower, ctx.paletteId(),
                foundationDepth, foundation, true, true
        ).interpret(plan, origin, ctx.world());
        out.addAll(wblocks);
    }

    public static void applyBridgeRoute(List<PlannedBlock> out,
                                        MetaAssemblyEngine.Context ctx,
                                        BlockPos origin,
                                        Map<String, Object> op,
                                        Adapter adapter) {
        int[] p0 = AssemblyRasterOps.parsePoint(op.get("from"));
        int[] p1 = AssemblyRasterOps.parsePoint(op.get("to"));
        int width = adapter.clamp(adapter.i(op.get("width"), 5), 3, 25);

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
            extra.put("paletteId", ctx.paletteId());
            extra.put("layout", java.util.Map.of("entranceFacing", forward.asString().toUpperCase(Locale.ROOT)));
            if (op.get("terrainAdaptation") != null) extra.put("terrainAdaptation", op.get("terrainAdaptation"));
            bs.setExtra(extra);

            List<PlannedBlock> bBlocks = new BridgeGenerator().generate(bs, origin.add(p0[0], p0[1], p0[2]), ctx.world()).getBlocks();
            out.addAll(bBlocks);
            return;
        }

        BlockState deck = adapter.pick(ctx, op, "deck", "BRIDGE_DECK", 0xA56101L, Blocks.OAK_PLANKS.getDefaultState());
        BlockState rail = adapter.pick(ctx, op, "rail", "BRIDGE_RAIL", 0xA56102L, Blocks.OAK_FENCE.getDefaultState());
        BlockState pier = adapter.pick(ctx, op, "pier", "WALL_FOUNDATION", 0xA56103L, Blocks.COBBLESTONE.getDefaultState());
        BlockPos a = origin.add(p0[0], p0[1], p0[2]);
        BlockPos b = origin.add(p1[0], p1[1], p1[2]);

        List<BlockPos> center = AssemblyRasterOps.rasterizeLine2D(a, b, ctx.world(), false, 0);
        int deckY = Math.max(a.getY(), b.getY());
        if (p0[1] == 0 && p1[1] == 0) {
            int max = deckY;
            for (BlockPos p : center) {
                int top = ctx.world().getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, p.getX(), p.getZ());
                max = Math.max(max, top + 2);
            }
            deckY = max;
        }
        int absDx = Math.abs(b.getX() - a.getX());
        int absDz = Math.abs(b.getZ() - a.getZ());
        boolean widthAlongZ = absDx >= absDz;

        for (BlockPos p : center) {
            for (int wOff = -width / 2; wOff <= width / 2; wOff++) {
                BlockPos dp = widthAlongZ ? p.add(0, deckY - p.getY(), wOff) : p.add(wOff, deckY - p.getY(), 0);
                out.add(new PlannedBlock(dp, deck));
                if (Math.abs(wOff) == width / 2) out.add(new PlannedBlock(dp.up(), rail));
                if ((p.getManhattanDistance(a) % 5) == 0 && wOff == 0) {
                    int y = deckY - 1;
                    for (int k = 0; k < 128; k++) {
                        BlockPos pp = new BlockPos(dp.getX(), y - k, dp.getZ());
                        BlockState cur = ctx.world().getBlockState(pp);
                        if (!cur.isAir()) break;
                        out.add(new PlannedBlock(pp, pier));
                    }
                }
            }
        }
    }
}
