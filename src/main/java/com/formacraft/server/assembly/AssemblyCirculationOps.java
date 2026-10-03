package com.formacraft.server.assembly;

import com.formacraft.common.build.PlannedBlock;
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
        String error = flightError(op.get("from"), op.get("to"));
        if (error != null) throw new IllegalArgumentException(error);
        int[] a = point(op.get("from")), b = point(op.get("to"));
        int width = adapter.clamp(adapter.i(op.get("width"), 2), 1, 15);
        boolean carve = adapter.bool(op.get("carve"), true);
        int clearH = adapter.clamp(adapter.i(op.get("clearHeight"), adapter.i(op.get("clear_h"), 3)), 0, 16);
        boolean support = adapter.bool(op.get("support"), true);
        int dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        int run = Math.max(Math.abs(dx), Math.abs(dz));
        long planned = (long) (run + 1) * width * (1 + (support ? 1 : 0) + (carve ? clearH : 0));
        if (planned > 100_000) throw new IllegalArgumentException("STAIR_SYSTEM exceeds 100000 planned block operations");
        BlockState stairMat = adapter.pick(ctx, op, "stairs", "STAIR", 0xA57470L, Blocks.STONE_BRICK_STAIRS.getDefaultState());
        BlockState floorMat = adapter.pick(ctx, op, "floor", "FLOORING", 0xA57471L, Blocks.SMOOTH_STONE.getDefaultState());
        BlockState supportMat = adapter.pick(ctx, op, "supportMaterial", "FOUNDATION", 0xA57472L, floorMat);
        if (!(stairMat.getBlock() instanceof net.minecraft.block.StairsBlock) || floorMat.isAir())
            throw new IllegalArgumentException("STAIR_SYSTEM requires stairs material and a non-air floor");
        stairMat = stairMat.with(net.minecraft.state.property.Properties.BLOCK_HALF, net.minecraft.block.enums.BlockHalf.BOTTOM)
            .with(net.minecraft.state.property.Properties.STAIR_SHAPE, net.minecraft.block.enums.StairShape.STRAIGHT);
        Direction direction = dx != 0 ? (dx > 0 ? Direction.EAST : Direction.WEST)
            : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        Direction lateral = direction.getAxis() == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
        int previousY = a[1];
        int firstOffset = -(width / 2);
        for (int i = 0; i <= run; i++) {
            int x = a[0] + direction.getOffsetX() * i;
            int z = a[2] + direction.getOffsetZ() * i;
            int y = run == 0 ? a[1] : (int) Math.round(a[1] + dy * (i / (double) run));
            BlockState tread = y == previousY ? floorMat : stairMat.with(
                net.minecraft.state.property.Properties.HORIZONTAL_FACING, y > previousY ? direction : direction.getOpposite());
            for (int lane = 0; lane < width; lane++) {
                int offset = firstOffset + lane;
                int wx = x + lateral.getOffsetX() * offset, wz = z + lateral.getOffsetZ() * offset;
                // Every sample, including both endpoints, owns its tread, support and clearance.
                adapter.put(out, ctx, origin, wx, y, wz, tread);
                if (support) adapter.put(out, ctx, origin, wx, y - 1, wz, supportMat);
                if (carve) for (int h = 1; h <= clearH; h++) adapter.put(out, ctx, origin, wx, y + h, wz, Blocks.AIR.getDefaultState());
            }
            previousY = y;
        }
    }

    /** Straight, axis-aligned flights only; turns require explicit landing/flight operations. */
    public static String flightError(Object from, Object to) {
        try {
            int[] a = point(from), b = point(to);
            long dx = (long) b[0] - a[0], dy = (long) b[1] - a[1], dz = (long) b[2] - a[2];
            if (dx != 0 && dz != 0) return "STAIR_SYSTEM requires an axis-aligned flight; split turns at landings";
            long run = Math.max(Math.abs(dx), Math.abs(dz));
            if (run > 4096) return "STAIR_SYSTEM horizontal run exceeds 4096 blocks";
            if (Math.abs(dy) > run) return "STAIR_SYSTEM rise exceeds horizontal run; add flights or landings";
            return null;
        } catch (IllegalArgumentException exception) { return exception.getMessage(); }
    }
    private static int[] point(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("STAIR_SYSTEM endpoints must contain integer x/y/z");
        int[] point = new int[3]; String[] axes = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            try { point[i] = new java.math.BigDecimal(String.valueOf(map.get(axes[i]))).intValueExact(); }
            catch (NumberFormatException | ArithmeticException exception) {
                throw new IllegalArgumentException("STAIR_SYSTEM endpoints must contain integer x/y/z");
            }
        }
        return point;
    }
}
