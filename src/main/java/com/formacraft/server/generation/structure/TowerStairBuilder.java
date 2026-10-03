package com.formacraft.server.generation.structure;

import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.List;

/** Adjacent square spiral: full-block corner landings and one rising stair on each edge. */
final class TowerStairBuilder {
    private TowerStairBuilder() {}
    private static final int[][] RING = {{-1,-1},{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0}};
    private static final Direction[] UPHILL = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};
    static void append(List<PlannedBlock> out, BlockPos origin, int topY, BlockState landing) {
        if (topY < 0 || landing == null || landing.isAir()) throw new IllegalArgumentException("Invalid tower stair landing");
        for (int i = 0; i <= topY * 2; i++) {
            int index = i % 8, y = (i + 1) / 2;
            BlockPos pos = origin.add(RING[index][0], y, RING[index][1]);
            BlockState tread = (index % 2 == 0) ? landing : Blocks.OAK_STAIRS.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, UPHILL[index / 2]);
            out.add(new PlannedBlock(pos, tread));
            out.add(new PlannedBlock(pos.up(), Blocks.AIR.getDefaultState()));
            out.add(new PlannedBlock(pos.up(2), Blocks.AIR.getDefaultState()));
        }
    }
}
