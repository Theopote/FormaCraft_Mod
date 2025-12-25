package com.formacraft.server.skeleton.rect;

import com.formacraft.common.skeleton.rect.RectEnclosurePlan;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.skeleton.SkeletonInterpreter;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * RectEnclosureInterpreter: builds a perimeter wall around a rectangle.
 * Includes a gate opening centered on one side.
 */
public final class RectEnclosureInterpreter implements SkeletonInterpreter<RectEnclosurePlan> {
    private final BlockState wall;
    private final BlockState cap;

    public RectEnclosureInterpreter(BlockState wall, BlockState cap) {
        this.wall = wall;
        this.cap = cap;
    }

    @Override
    public List<PlannedBlock> interpret(RectEnclosurePlan plan, BlockPos origin, ServerWorld world) {
        int w = Math.max(7, plan.width);
        int d = Math.max(7, plan.depth);
        int h = Math.max(2, plan.wallHeight);
        int t = Math.max(1, plan.thickness);

        int halfW = w / 2;
        int halfD = d / 2;

        int gateW = Math.max(1, plan.gateWidth);
        int gateHalf = gateW / 2;

        List<PlannedBlock> blocks = new ArrayList<>(Math.max(2000, (w + d) * h * t));

        // perimeter bands at thickness t
        for (int layer = 0; layer < t; layer++) {
            int xMin = -halfW + layer;
            int xMax = halfW - layer;
            int zMin = -halfD + layer;
            int zMax = halfD - layer;

            // north/south edges (z fixed)
            for (int x = xMin; x <= xMax; x++) {
                placeWallColumn(blocks, origin, x, zMin, h, plan, gateHalf); // north
                placeWallColumn(blocks, origin, x, zMax, h, plan, gateHalf); // south
            }
            // west/east edges (x fixed)
            for (int z = zMin; z <= zMax; z++) {
                placeWallColumn(blocks, origin, xMin, z, h, plan, gateHalf); // west
                placeWallColumn(blocks, origin, xMax, z, h, plan, gateHalf); // east
            }
        }

        // cap line (simple)
        int capY = h;
        for (int x = -halfW + 1; x <= halfW - 1; x++) {
            blocks.add(new PlannedBlock(origin.add(x, capY, -halfD), cap));
            blocks.add(new PlannedBlock(origin.add(x, capY, halfD), cap));
        }
        for (int z = -halfD + 1; z <= halfD - 1; z++) {
            blocks.add(new PlannedBlock(origin.add(-halfW, capY, z), cap));
            blocks.add(new PlannedBlock(origin.add(halfW, capY, z), cap));
        }

        return blocks;
    }

    private void placeWallColumn(List<PlannedBlock> blocks, BlockPos origin, int x, int z, int h, RectEnclosurePlan plan, int gateHalf) {
        if (isGateOpening(x, z, plan, gateHalf)) {
            // carve opening 2 blocks tall
            blocks.add(new PlannedBlock(origin.add(x, 1, z), Blocks.AIR.getDefaultState()));
            blocks.add(new PlannedBlock(origin.add(x, 2, z), Blocks.AIR.getDefaultState()));
            return;
        }
        for (int y = 0; y <= h; y++) {
            blocks.add(new PlannedBlock(origin.add(x, y, z), wall));
        }
    }

    private boolean isGateOpening(int x, int z, RectEnclosurePlan plan, int gateHalf) {
        Direction side = plan.gateSide;
        // gate at center of selected side
        return switch (side) {
            case SOUTH -> z > 0 && Math.abs(x) <= gateHalf;
            case NORTH -> z < 0 && Math.abs(x) <= gateHalf;
            case EAST -> x > 0 && Math.abs(z) <= gateHalf;
            case WEST -> x < 0 && Math.abs(z) <= gateHalf;
            default -> z > 0 && Math.abs(x) <= gateHalf;
        };
    }
}


