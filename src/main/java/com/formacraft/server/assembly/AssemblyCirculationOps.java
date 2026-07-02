package com.formacraft.server.assembly;

import com.formacraft.server.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Map;

/**
 * Circulation/stairs assembly ops extracted from {@link MetaAssemblyEngine}.
 */
public final class AssemblyCirculationOps {
    private AssemblyCirculationOps() {}

    public interface Adapter {
        void put(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, int x, int y, int z, BlockState state);
        BlockState pick(MetaAssemblyEngine.Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback);
        int i(Object v, int def);
        boolean bool(Object v, boolean def);
        int clamp(int v, int min, int max);
    }

    public static void applyStairSystem(List<PlannedBlock> out,
                                        MetaAssemblyEngine.Context ctx,
                                        BlockPos origin,
                                        Map<String, Object> op,
                                        Adapter adapter) {
        int[] a = AssemblyRasterOps.parsePoint(op.get("from"));
        int[] b = AssemblyRasterOps.parsePoint(op.get("to"));

        int width = adapter.clamp(adapter.i(op.get("width"), 2), 1, 15);
        boolean carve = adapter.bool(op.get("carve"), true);
        int clearH = adapter.clamp(adapter.i(op.get("clearHeight"), adapter.i(op.get("clear_h"), 3)), 0, 16);
        boolean support = adapter.bool(op.get("support"), true);

        BlockState stairMat = adapter.pick(ctx, op, "stairs", "STAIR", 0xA57470L, Blocks.STONE_BRICK_STAIRS.getDefaultState());
        BlockState floorMat = adapter.pick(ctx, op, "floor", "FLOORING", 0xA57471L, Blocks.SMOOTH_STONE.getDefaultState());
        BlockState supportMat = adapter.pick(ctx, op, "supportMaterial", "FOUNDATION", 0xA57472L, floorMat);

        int dx = b[0] - a[0];
        int dy = b[1] - a[1];
        int dz = b[2] - a[2];
        int run = Math.max(Math.max(Math.abs(dx), Math.abs(dz)), Math.abs(dy));
        run = Math.max(run, 1);

        Direction horizDir;
        if (Math.abs(dx) >= Math.abs(dz)) horizDir = (dx >= 0) ? Direction.EAST : Direction.WEST;
        else horizDir = (dz >= 0) ? Direction.SOUTH : Direction.NORTH;

        int prevX = a[0], prevY = a[1], prevZ = a[2];
        for (int i = 0; i <= run; i++) {
            double t = i / (double) run;
            int x = (int) Math.round(a[0] + dx * t);
            int z = (int) Math.round(a[2] + dz * t);
            int y = (int) Math.round(a[1] + dy * t);

            int deltaY = y - prevY;
            if (deltaY > 1) y = prevY + 1;
            if (deltaY < -1) y = prevY - 1;

            Direction lateral = (horizDir == Direction.EAST || horizDir == Direction.WEST) ? Direction.SOUTH : Direction.EAST;
            int half = width / 2;

            for (int wOff = -half; wOff <= half; wOff++) {
                int wx = x + lateral.getOffsetX() * wOff;
                int wz = z + lateral.getOffsetZ() * wOff;

                if (y > prevY) {
                    int sx = prevX + lateral.getOffsetX() * wOff;
                    int sz = prevZ + lateral.getOffsetZ() * wOff;
                    BlockState s = stairMat;
                    if (s.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING)) {
                        s = s.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, horizDir);
                    }
                    adapter.put(out, ctx, origin, sx, prevY, sz, s);
                } else if (y < prevY) {
                    BlockState s = stairMat;
                    Direction f = horizDir.getOpposite();
                    if (s.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING)) {
                        s = s.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, f);
                    }
                    adapter.put(out, ctx, origin, wx, y, wz, s);
                } else {
                    adapter.put(out, ctx, origin, wx, y, wz, floorMat);
                }

                if (support) {
                    adapter.put(out, ctx, origin, wx, y - 1, wz, supportMat);
                }

                if (carve && clearH > 0) {
                    for (int yy = 1; yy <= clearH; yy++) {
                        adapter.put(out, ctx, origin, wx, y + yy, wz, Blocks.AIR.getDefaultState());
                    }
                }
            }

            prevX = x;
            prevY = y;
            prevZ = z;
        }
    }
}
