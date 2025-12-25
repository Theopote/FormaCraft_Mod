package com.formacraft.server.skeleton.linear;

import com.formacraft.common.skeleton.linear.LinearPathPlan;
import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.skeleton.SkeletonInterpreter;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

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
    private final BlockState crenel;
    private final BlockState tower;

    public LinearWallInterpreter(BlockState wall, BlockState accent, boolean mixWallBlocks, BlockState walkway, BlockState crenel, BlockState tower) {
        this.wall = wall;
        this.accent = accent;
        this.mixWallBlocks = mixWallBlocks;
        this.walkway = walkway;
        this.crenel = crenel;
        this.tower = tower;
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
                for (int y = 0; y < segHeight; y++) {
                    BlockPos bp = new BlockPos(p.getX() + tx, baseY + y, p.getZ() + tz);
                    if (BuildConstraintContext.allow(bp)) blocks.add(new PlannedBlock(bp, pickWallBlock(i, y, t)));
                }
            }

            // walkway (top)
            int walkwayY = baseY + (height - 1) + towerExtraH;
            for (int t = -halfT; t <= halfT; t++) {
                BlockPos wp = new BlockPos(p.getX() + rx * t, walkwayY, p.getZ() + rz * t);
                if (BuildConstraintContext.allow(wp)) blocks.add(new PlannedBlock(wp, walkway));
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

    private BlockState pickWallBlock(int i, int y, int t) {
        if (!mixWallBlocks) return wall;
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
}


