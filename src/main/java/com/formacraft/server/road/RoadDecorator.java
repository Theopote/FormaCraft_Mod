package com.formacraft.server.road;

import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.server.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * RoadDecorator:
 * Converts a sampled path into PlannedBlocks:
 * - normal road surface
 * - stairs on +/-1 dy
 * - simple bridge deck on water/gap
 * - optional headroom clearing
 */
public final class RoadDecorator {
    private RoadDecorator() {}

    public static List<PlannedBlock> decorate(ServerWorld world,
                                              List<BlockPos> path,
                                              int width,
                                              int clearHeight,
                                              BlockState road,
                                              BlockState border,
                                              boolean useBorder,
                                              BlockState bridgeDeck,
                                              BlockState bridgeRail) {
        if (path == null || path.isEmpty()) return List.of();
        int w = Math.max(1, width);
        int half = w / 2;
        int ch = Math.max(0, clearHeight);

        List<PlannedBlock> out = new ArrayList<>(Math.max(200, path.size() * w * 2));

        for (int i = 0; i < path.size(); i++) {
            BlockPos p = path.get(i);
            BlockPos prev = (i > 0) ? path.get(i - 1) : null;
            BlockPos next = (i + 1 < path.size()) ? path.get(i + 1) : null;

            // approximate tangent direction (cardinal)
            int dx = 0, dz = 0;
            if (next != null) {
                dx = Integer.compare(next.getX() - p.getX(), 0);
                dz = Integer.compare(next.getZ() - p.getZ(), 0);
            } else if (prev != null) {
                dx = Integer.compare(p.getX() - prev.getX(), 0);
                dz = Integer.compare(p.getZ() - prev.getZ(), 0);
            }
            if (dx == 0 && dz == 0) { dx = 1; dz = 0; }
            int rx = -dz;
            int rz = dx;

            int dyFromPrev = (prev != null) ? (p.getY() - prev.getY()) : 0;
            boolean step = Math.abs(dyFromPrev) == 1;
            boolean bridge = isBridgeUnder(world, p);

            Direction facing = dirFromDelta(dx, dz);
            // For downward movement, stairs should face towards the higher block.
            if (dyFromPrev < 0 && prev != null) facing = dirFromDelta(Integer.compare(prev.getX() - p.getX(), 0),
                    Integer.compare(prev.getZ() - p.getZ(), 0));

            for (int ww = -half; ww <= half; ww++) {
                int x2 = p.getX() + rx * ww;
                int z2 = p.getZ() + rz * ww;
                BlockPos bp = new BlockPos(x2, p.getY(), z2);

                BlockState base = road;
                if (bridge) base = bridgeDeck;
                if (step) base = asStairsIfPossible(base, facing);

                if (BuildConstraintContext.allow(bp)) out.add(new PlannedBlock(bp, base));

                // clear headroom (walkable)
                for (int h = 1; h <= ch; h++) {
                    BlockPos up = bp.up(h);
                    if (BuildConstraintContext.allow(up)) out.add(new PlannedBlock(up, Blocks.AIR.getDefaultState()));
                }
            }

            if (useBorder) {
                BlockPos b1 = new BlockPos(p.getX() + rx * (half + 1), p.getY(), p.getZ() + rz * (half + 1));
                BlockPos b2 = new BlockPos(p.getX() - rx * (half + 1), p.getY(), p.getZ() - rz * (half + 1));
                if (BuildConstraintContext.allow(b1)) out.add(new PlannedBlock(b1, border));
                if (BuildConstraintContext.allow(b2)) out.add(new PlannedBlock(b2, border));
            }

            // simple bridge rails: put fences at the edge if we're bridging
            if (bridge && w >= 2) {
                int ex = p.getX() + rx * (half + 1);
                int ez = p.getZ() + rz * (half + 1);
                int ex2 = p.getX() - rx * (half + 1);
                int ez2 = p.getZ() - rz * (half + 1);
                BlockPos r1 = new BlockPos(ex, p.getY() + 1, ez);
                BlockPos r2 = new BlockPos(ex2, p.getY() + 1, ez2);
                if (BuildConstraintContext.allow(r1)) out.add(new PlannedBlock(r1, bridgeRail));
                if (BuildConstraintContext.allow(r2)) out.add(new PlannedBlock(r2, bridgeRail));
            }
        }

        return out;
    }

    private static boolean isBridgeUnder(ServerWorld world, BlockPos p) {
        if (world == null || p == null) return false;
        BlockState below = world.getBlockState(p.down());
        return !below.getFluidState().isEmpty() || below.isAir();
    }

    private static BlockState asStairsIfPossible(BlockState base, Direction facing) {
        if (base == null) return Blocks.STONE_BRICK_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING, facing);
        if (base.getBlock() instanceof StairsBlock) {
            if (base.contains(Properties.HORIZONTAL_FACING)) return base.with(Properties.HORIZONTAL_FACING, facing);
            return base;
        }
        // If the selected road material isn't stairs, use stone brick stairs as a safe default.
        return Blocks.STONE_BRICK_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING, facing);
    }

    private static Direction dirFromDelta(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}


