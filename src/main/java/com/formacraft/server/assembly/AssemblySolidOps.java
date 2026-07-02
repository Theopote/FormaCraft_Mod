package com.formacraft.server.assembly;

import com.formacraft.server.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Solid/polygon/roof assembly ops extracted from {@link MetaAssemblyEngine}.
 */
public final class AssemblySolidOps {
    private AssemblySolidOps() {}

    public interface Adapter {
        void put(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, int x, int y, int z, BlockState state);
        BlockState pick(MetaAssemblyEngine.Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback);
        int i(Object v, int def);
        double d(Object v, double def);
        double clamp(double v, double min, double max);
        boolean bool(Object v, boolean def);
        String str(Object v, String def);
        int clamp(int v, int min, int max);
    }

    public static void applyExtrudePolygon(List<PlannedBlock> out,
                                           MetaAssemblyEngine.Context ctx,
                                           BlockPos origin,
                                           Map<String, Object> op,
                                           Adapter adapter) {
        int h = adapter.clamp(adapter.i(op.get("h"), adapter.i(op.get("height"), 12)), 1, 255);
        boolean hollow = adapter.bool(op.get("hollow"), false);
        int thickness = adapter.clamp(adapter.i(op.get("thickness"), 1), 1, 16);

        List<int[]> pts = new ArrayList<>();
        String shape = adapter.str(op.get("shape"), "RECT").trim().toUpperCase(Locale.ROOT);
        if ("RECT".equals(shape)) {
            int w = adapter.clamp(adapter.i(op.get("w"), 11), 1, 255);
            int d = adapter.clamp(adapter.i(op.get("d"), 11), 1, 255);
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
                        int px = adapter.i(pm.get("x"), 0);
                        int pz = adapter.i(pm.get("z"), 0);
                        pts.add(new int[]{px, pz});
                    }
                }
            }
            if (pts.size() < 3) return;
        }

        BlockState solid = adapter.pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0xA55401L, Blocks.STONE_BRICKS.getDefaultState());
        BlockState wall = adapter.pick(ctx, op, "wall", "WALL_BASE", 0xA55402L, solid);

        int[] bb = bounds(pts);
        int xMin = bb[0], xMax = bb[1], zMin = bb[2], zMax = bb[3];

        for (int x = xMin; x <= xMax; x++) {
            for (int z = zMin; z <= zMax; z++) {
                boolean inside = AssemblyProfilePolygonOps.pointInPolyXZ(x, z, pts);
                if (!inside) continue;
                for (int y = 0; y < h; y++) {
                    if (!hollow) {
                        adapter.put(out, ctx, origin, x, y, z, solid);
                    } else {
                        boolean boundary = false;
                        for (int k = 1; k <= thickness && !boundary; k++) {
                            if (!AssemblyProfilePolygonOps.pointInPolyXZ(x + k, z, pts)
                                    || !AssemblyProfilePolygonOps.pointInPolyXZ(x - k, z, pts)
                                    || !AssemblyProfilePolygonOps.pointInPolyXZ(x, z + k, pts)
                                    || !AssemblyProfilePolygonOps.pointInPolyXZ(x, z - k, pts)) {
                                boundary = true;
                            }
                        }
                        if (boundary) adapter.put(out, ctx, origin, x, y, z, wall);
                        else adapter.put(out, ctx, origin, x, y, z, Blocks.AIR.getDefaultState());
                    }
                }
            }
        }
    }

    public static void applyRoofCover(List<PlannedBlock> out,
                                      MetaAssemblyEngine.Context ctx,
                                      BlockPos origin,
                                      Map<String, Object> op,
                                      Adapter adapter) {
        String type = adapter.str(op.get("type"), "FLAT").trim().toUpperCase(Locale.ROOT);
        int w = adapter.clamp(adapter.i(op.get("w"), 11), 3, 255);
        int d = adapter.clamp(adapter.i(op.get("d"), 11), 3, 255);
        int yBase = adapter.i(op.get("y"), 0);
        int overhang = adapter.clamp(adapter.i(op.get("overhang"), 0), 0, 8);
        int rise = adapter.clamp(adapter.i(op.get("rise"), Math.max(2, Math.min(6, Math.max(w, d) / 6))), 1, 32);
        double curvaturePower = adapter.clamp(adapter.d(op.get("curvaturePower"), adapter.d(op.get("curvature_power"), 1.0)), 0.1, 3.0);
        double cornerLift = adapter.clamp(adapter.d(op.get("cornerLift"), adapter.d(op.get("corner_lift"), 0.0)), 0.0, 2.0);

        BlockState roof = adapter.pick(ctx, op, "roof", "ROOF_TILE", 0xA55501L, Blocks.STONE_BRICK_STAIRS.getDefaultState());
        BlockState slab = adapter.pick(ctx, op, "slab", "FLOOR_SLAB", 0xA55502L, Blocks.SMOOTH_STONE_SLAB.getDefaultState());

        int hx = w / 2;
        int hz = d / 2;
        int x0 = -hx - overhang, x1 = hx + overhang;
        int z0 = -hz - overhang, z1 = hz + overhang;

        if (type.contains("FLAT")) {
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) adapter.put(out, ctx, origin, x, yBase, z, slab);
            return;
        }

        boolean ridgeAlongX = w >= d;
        if (!(roof.getBlock() instanceof StairsBlock)) {
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) adapter.put(out, ctx, origin, x, yBase, z, roof);
            return;
        }

        if (ridgeAlongX) {
            int spanZ = z1 - z0;
            for (int step = 0; step < rise; step++) {
                double t = (double) step / Math.max(1, rise - 1);
                double curvedT = Math.pow(t, curvaturePower);
                int y = yBase + (int) Math.round(curvedT * rise);
                int zz0 = z0 + (int) Math.round(curvedT * spanZ / 2);
                int zz1 = z1 - (int) Math.round(curvedT * spanZ / 2);
                if (zz0 > zz1) break;

                int cornerLiftY = (int) Math.round(cornerLift * (1.0 - Math.abs(t - 0.5) * 2.0));
                BlockState sN = roof.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING) ? roof.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, Direction.NORTH) : roof;
                BlockState sS = roof.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING) ? roof.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, Direction.SOUTH) : roof;
                for (int x = x0; x <= x1; x++) {
                    int finalY = y + (cornerLiftY > 0 && (x == x0 || x == x1) ? cornerLiftY : 0);
                    adapter.put(out, ctx, origin, x, finalY, zz0, sS);
                    adapter.put(out, ctx, origin, x, finalY, zz1, sN);
                }
            }
        } else {
            int spanX = x1 - x0;
            for (int step = 0; step < rise; step++) {
                double t = (double) step / Math.max(1, rise - 1);
                double curvedT = Math.pow(t, curvaturePower);
                int y = yBase + (int) Math.round(curvedT * rise);
                int xx0 = x0 + (int) Math.round(curvedT * spanX / 2);
                int xx1 = x1 - (int) Math.round(curvedT * spanX / 2);
                if (xx0 > xx1) break;

                int cornerLiftY = (int) Math.round(cornerLift * (1.0 - Math.abs(t - 0.5) * 2.0));
                BlockState sW = roof.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING) ? roof.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, Direction.WEST) : roof;
                BlockState sE = roof.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING) ? roof.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, Direction.EAST) : roof;
                for (int z = z0; z <= z1; z++) {
                    int finalY = y + (cornerLiftY > 0 && (z == z0 || z == z1) ? cornerLiftY : 0);
                    adapter.put(out, ctx, origin, xx0, finalY, z, sE);
                    adapter.put(out, ctx, origin, xx1, finalY, z, sW);
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
}
