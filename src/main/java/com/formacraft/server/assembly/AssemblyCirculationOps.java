package com.formacraft.server.assembly;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.palette.component.MaterialBlockFamily;
import com.formacraft.common.patch.BlockPatchTargetResolver;
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

    public static final class InvalidStairMaterial extends IllegalArgumentException {
        private InvalidStairMaterial(String message) { super(message); }
    }

    public interface Adapter {
        void put(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, int x, int y, int z, BlockState state);
        BlockState pick(MetaAssemblyEngine.Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback);
        int i(Object v, int def);
        boolean bool(Object v, boolean def);
        int clamp(int v, int min, int max);
    }

    private static BlockState stairMaterial(Adapter adapter, MetaAssemblyEngine.Context ctx, Map<?, ?> op,
                                            String key, String role, long salt, BlockState fallback) {
        Object authored = op.get(key);
        if (!(authored instanceof String name)) {
            return adapter.pick(ctx, op, key, role, salt, fallback);
        }
        BlockState state = BlockPatchTargetResolver.parse(name);
        if (state == null) {
            throw new InvalidStairMaterial("STAIR_SYSTEM " + key + " must resolve to a registered block state: " + name);
        }
        return state;
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
        // Rising steps also carve the previous column (transition headroom / floor openings).
        long planned = (long) (run + 1) * width * (1 + (support ? 1 : 0) + (carve ? clearH : 0))
                + (carve ? (long) run * width * clearH : 0);
        if (planned > 100_000) throw new IllegalArgumentException("STAIR_SYSTEM exceeds 100000 planned block operations");
        BlockState floorMat = stairMaterial(adapter, ctx, op, "floor", "FLOORING", 0xA57471L, Blocks.SMOOTH_STONE.getDefaultState());
        BlockState stairDefault = Blocks.STONE_BRICK_STAIRS.getDefaultState();
        BlockState stairMat;
        if (dy != 0 && op.get("stairs") == null && op.get("floor") instanceof String floor) {
            String familyStairs = MaterialBlockFamily.stairs(floor);
            if (familyStairs == null) {
                throw new InvalidStairMaterial("STAIR_SYSTEM has no registered stair variant for floor "
                        + floor + "; specify an explicit stairs block.");
            }
            stairMat = BlockPatchTargetResolver.parse(familyStairs);
        } else {
            stairMat = stairMaterial(adapter, ctx, op, "stairs", "STAIR", 0xA57470L, stairDefault);
        }
        BlockState supportMat = stairMaterial(adapter, ctx, op, "supportMaterial", "FOUNDATION", 0xA57472L, floorMat);
        if (stairMat == null || !(stairMat.getBlock() instanceof net.minecraft.block.StairsBlock)) {
            throw new InvalidStairMaterial("STAIR_SYSTEM stairs must resolve to a registered stair block: " + op.get("stairs"));
        }
        if (floorMat.isAir()) {
            throw new InvalidStairMaterial("STAIR_SYSTEM floor must be a non-air block");
        }
        stairMat = stairMat.with(net.minecraft.state.property.Properties.BLOCK_HALF, net.minecraft.block.enums.BlockHalf.BOTTOM)
            .with(net.minecraft.state.property.Properties.STAIR_SHAPE, net.minecraft.block.enums.StairShape.STRAIGHT);
        Direction direction = dx != 0 ? (dx > 0 ? Direction.EAST : Direction.WEST)
            : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        Direction lateral = direction.getAxis() == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
        int previousY = a[1];
        int prevX = a[0], prevZ = a[2];
        int firstOffset = -(width / 2);
        for (int i = 0; i <= run; i++) {
            int x = a[0] + direction.getOffsetX() * i;
            int z = a[2] + direction.getOffsetZ() * i;
            int y = run == 0 ? a[1] : (int) Math.round(a[1] + dy * (i / (double) run));
            BlockState tread = y == previousY ? floorMat : stairMat.with(
                net.minecraft.state.property.Properties.HORIZONTAL_FACING, y > previousY ? direction : direction.getOpposite());
            boolean rising = i > 0 && y > previousY;
            boolean descending = i > 0 && y < previousY;
            for (int lane = 0; lane < width; lane++) {
                int offset = firstOffset + lane;
                int wx = x + lateral.getOffsetX() * offset, wz = z + lateral.getOffsetZ() * offset;
                // Every sample, including both endpoints, owns its tread, support and clearance.
                adapter.put(out, ctx, origin, wx, y, wz, tread);
                if (support) adapter.put(out, ctx, origin, wx, y - 1, wz, supportMat);
                if (carve) {
                    for (int h = 1; h <= clearH; h++)
                        adapter.put(out, ctx, origin, wx, y + h, wz, Blocks.AIR.getDefaultState());
                    // Upward: keep the previous column clear through the new headroom so floor
                    // openings include step-up body clearance (avoids head-hits on the slab edge).
                    if (rising) {
                        int pwx = prevX + lateral.getOffsetX() * offset;
                        int pwz = prevZ + lateral.getOffsetZ() * offset;
                        for (int h = 1; h <= clearH; h++)
                            adapter.put(out, ctx, origin, pwx, y + h, pwz, Blocks.AIR.getDefaultState());
                    }
                    // Downward: clear the destination column at the previous (higher) headroom so
                    // the floor hole stays open when stepping down onto the lower tread.
                    if (descending) {
                        for (int h = 1; h <= clearH; h++)
                            adapter.put(out, ctx, origin, wx, previousY + h, wz, Blocks.AIR.getDefaultState());
                    }
                }
            }
            prevX = x;
            prevZ = z;
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
