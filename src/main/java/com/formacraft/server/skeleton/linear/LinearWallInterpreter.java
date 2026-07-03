package com.formacraft.server.skeleton.linear;

import com.formacraft.common.skeleton.linear.LinearPathPlan;
import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import com.formacraft.server.skeleton.SkeletonInterpreter;
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
 * LinearWallInterpreter: turns a LinearPathPlan into PlannedBlocks.
 *
 * v1 behavior intentionally mirrors GreatWallGenerator's core features:
 * - wall volume with thickness + height
 * - walkway top surface
 * - crenels (optional)
 * - watchtowers every N blocks (simple, on centerline)
 *
 * Note: styling/material selection remains the generator's responsibility (pass in blocks).
 */
public final class LinearWallInterpreter implements SkeletonInterpreter<LinearPathPlan> {
    private final BlockState wall;
    private final BlockState accent;
    private final boolean mixWallBlocks;
    private final BlockState walkway;
    private final BlockState walkwayStairs;
    private final BlockState crenel;
    private final BlockState tower;
    private final String paletteId;
    private final int foundationDepth;
    private final BlockState foundationBlock;
    private final boolean allowWaterEdit;
    private final boolean allowLavaEdit;

    public LinearWallInterpreter(BlockState wall, BlockState accent, boolean mixWallBlocks, BlockState walkway, BlockState crenel, BlockState tower) {
        this(wall, accent, mixWallBlocks, walkway, null, crenel, tower, null, 0, null, true, true);
    }

    public LinearWallInterpreter(BlockState wall, BlockState accent, boolean mixWallBlocks, BlockState walkway, BlockState crenel, BlockState tower, String paletteId) {
        this.wall = wall;
        this.accent = accent;
        this.mixWallBlocks = mixWallBlocks;
        this.walkway = walkway;
        this.walkwayStairs = null;
        this.crenel = crenel;
        this.tower = tower;
        this.paletteId = paletteId;
        this.foundationDepth = 0;
        this.foundationBlock = null;
        this.allowWaterEdit = true;
        this.allowLavaEdit = true;
    }

    public LinearWallInterpreter(BlockState wall,
                                 BlockState accent,
                                 boolean mixWallBlocks,
                                 BlockState walkway,
                                 BlockState walkwayStairs,
                                 BlockState crenel,
                                 BlockState tower,
                                 String paletteId,
                                 int foundationDepth,
                                 BlockState foundationBlock,
                                 boolean allowWaterEdit,
                                 boolean allowLavaEdit) {
        this.wall = wall;
        this.accent = accent;
        this.mixWallBlocks = mixWallBlocks;
        this.walkway = walkway;
        this.walkwayStairs = walkwayStairs;
        this.crenel = crenel;
        this.tower = tower;
        this.paletteId = paletteId;
        this.foundationDepth = Math.max(0, Math.min(32, foundationDepth));
        this.foundationBlock = foundationBlock;
        this.allowWaterEdit = allowWaterEdit;
        this.allowLavaEdit = allowLavaEdit;
    }

    @Override
    public List<PlannedBlock> interpret(LinearPathPlan plan, BlockPos origin, ServerWorld world) {
        int length = plan.pathPoints.size() - 1;
        int thickness = plan.thickness;
        int height = plan.height;
        int towerSpacing = Math.max(8, plan.towerSpacing);
        boolean crenels = plan.crenels;

        List<PlannedBlock> blocks = new ArrayList<>(Math.max(8000, length * thickness * height));

        int halfT = thickness / 2;
        for (int i = 0; i < plan.pathPoints.size(); i++) {
            BlockPos p = plan.pathPoints.get(i);
            int baseY = p.getY();

            boolean isTower = (i % towerSpacing == 0);
            int towerExtraH = isTower ? 3 : 0;
            int segHeight = height + towerExtraH;

            // estimate perpendicular (right) direction using neighbors
            BlockPos prev = (i > 0) ? plan.pathPoints.get(i - 1) : p;
            BlockPos next = (i < plan.pathPoints.size() - 1) ? plan.pathPoints.get(i + 1) : p;
            int dx = next.getX() - prev.getX();
            int dz = next.getZ() - prev.getZ();

            // right vector = (-dz, dx) normalized to cardinal-ish steps
            int rx = Integer.compare(-dz, 0);
            int rz = Integer.compare(dx, 0);
            if (rx == 0 && rz == 0) { rx = 1; rz = 0; }

            // wall volume
            for (int t = -halfT; t <= halfT; t++) {
                int tx = rx * t;
                int tz = rz * t;

                // foundation: prevent gaps when the path has been smoothed above ground
                if (foundationDepth > 0) {
                    for (int k = 1; k <= foundationDepth; k++) {
                        BlockPos fp = new BlockPos(p.getX() + tx, baseY - k, p.getZ() + tz);
                        if (!BuildConstraintContext.allow(fp)) continue;
                        BlockState cur = world.getBlockState(fp);
                        boolean isAir = cur.isAir();
                        boolean isWater = cur.getBlock() == Blocks.WATER;
                        boolean isLava = cur.getBlock() == Blocks.LAVA;
                        if (isAir || (allowWaterEdit && isWater) || (allowLavaEdit && isLava)) {
                            blocks.add(new PlannedBlock(fp, foundationBlock != null ? foundationBlock : wall));
                        } else {
                            // stop when hitting solid, to avoid drilling into mountains
                            break;
                        }
                    }
                }

                for (int y = 0; y < segHeight; y++) {
                    BlockPos bp = new BlockPos(p.getX() + tx, baseY + y, p.getZ() + tz);
                    if (BuildConstraintContext.allow(bp)) blocks.add(new PlannedBlock(bp, pickWallBlock(world, i, y, t, bp)));
                }
            }

            // walkway (top)
            int walkwayY = baseY + (height - 1) + towerExtraH;

            // stair-casing on slopes:
            // - If next point is +1 higher, place stairs at current segment facing forward (uphill ramp).
            // - If current point is -1 lower than prev, place stairs at current segment facing backward (downhill ramp).
            Direction forwardDir = dirFromDelta(Integer.compare(dx, 0), Integer.compare(dz, 0));
            int dyToNext = next.getY() - p.getY();
            int dyFromPrev = p.getY() - prev.getY();
            boolean useStairs = false;
            Direction stairFacing = forwardDir;
            if (!isTower) {
                if (dyToNext == 1) {
                    useStairs = true;
                    stairFacing = forwardDir;
                } else if (dyFromPrev == -1) {
                    useStairs = true;
                    stairFacing = forwardDir.getOpposite();
                }
            }

            for (int t = -halfT; t <= halfT; t++) {
                BlockPos wp = new BlockPos(p.getX() + rx * t, walkwayY, p.getZ() + rz * t);
                if (BuildConstraintContext.allow(wp)) {
                    BlockState ws = walkway;
                    if (useStairs) ws = asStairsIfPossible(walkwayStairs != null ? walkwayStairs : walkway, stairFacing);
                    blocks.add(new PlannedBlock(wp, ws));
                }
            }

            // crenels on edges (alternating)
            if (crenels && (i % 2 == 0)) {
                int crenelY = baseY + height + towerExtraH;
                BlockPos c1 = new BlockPos(p.getX() + rx * (-halfT), crenelY, p.getZ() + rz * (-halfT));
                if (BuildConstraintContext.allow(c1)) blocks.add(new PlannedBlock(c1, crenel));
                BlockPos c2 = new BlockPos(p.getX() + rx * (halfT), crenelY, p.getZ() + rz * (halfT));
                if (BuildConstraintContext.allow(c2)) blocks.add(new PlannedBlock(c2, crenel));
            }

            // watchtower (very simple): a small hollow square around the center point
            if (isTower) {
                buildTower(blocks, p.getX(), baseY, p.getZ(), Math.max(2, halfT + 1), height + towerExtraH + 2);
            }
        }

        return blocks;
    }

    private BlockState pickWallBlock(ServerWorld world, int i, int y, int t, BlockPos pos) {
        // If a paletteId is provided, let palette drive the wall base variation.
        if (paletteId != null && !paletteId.isBlank()) {
            // Salt: keep stable across ticks; incorporate segment+layer
            long salt = (i * 1315423911L) ^ (y * 2654435761L) ^ (t * 97531L);
            BlockState fallback = mixWallBlocks ? pickWallBlockLegacy(i, y, t) : wall;
            return PaletteResolver.pick(world, paletteId, "WALL_BASE", pos, salt, fallback);
        }
        return mixWallBlocks ? pickWallBlockLegacy(i, y, t) : wall;
    }

    private BlockState pickWallBlockLegacy(int i, int y, int t) {
        int h = (i * 31 + y * 17 + t * 13);
        if ((h % 23) == 0) return accent;
        return wall;
    }

    private void buildTower(List<PlannedBlock> blocks, int x0, int y0, int z0, int r, int h) {
        for (int y = 0; y <= h; y++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    boolean edge = (Math.abs(x) == r) || (Math.abs(z) == r);
                    if (!edge) continue;
                    BlockPos p = new BlockPos(x0 + x, y0 + y, z0 + z);
                    if (BuildConstraintContext.allow(p)) blocks.add(new PlannedBlock(p, tower));
                }
            }
        }
        // top crenels
        for (int x = -r; x <= r; x++) {
            if ((x & 1) == 0) {
                BlockPos p1 = new BlockPos(x0 + x, y0 + h + 1, z0 - r);
                if (BuildConstraintContext.allow(p1)) blocks.add(new PlannedBlock(p1, Blocks.STONE_BRICK_WALL.getDefaultState()));
                BlockPos p2 = new BlockPos(x0 + x, y0 + h + 1, z0 + r);
                if (BuildConstraintContext.allow(p2)) blocks.add(new PlannedBlock(p2, Blocks.STONE_BRICK_WALL.getDefaultState()));
            }
        }
        for (int z = -r; z <= r; z++) {
            if ((z & 1) == 0) {
                BlockPos p1 = new BlockPos(x0 - r, y0 + h + 1, z0 + z);
                if (BuildConstraintContext.allow(p1)) blocks.add(new PlannedBlock(p1, Blocks.STONE_BRICK_WALL.getDefaultState()));
                BlockPos p2 = new BlockPos(x0 + r, y0 + h + 1, z0 + z);
                if (BuildConstraintContext.allow(p2)) blocks.add(new PlannedBlock(p2, Blocks.STONE_BRICK_WALL.getDefaultState()));
            }
        }
    }

    private static BlockState asStairsIfPossible(BlockState base, Direction facing) {
        if (base == null) return Blocks.STONE_BRICK_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING, facing);
        if (base.getBlock() instanceof StairsBlock) {
            if (base.contains(Properties.HORIZONTAL_FACING)) return base.with(Properties.HORIZONTAL_FACING, facing);
            return base;
        }
        // If walkway isn't stairs, use a safe default stairs material.
        return Blocks.STONE_BRICK_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING, facing);
    }

    private static Direction dirFromDelta(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}


