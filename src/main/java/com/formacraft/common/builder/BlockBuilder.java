package com.formacraft.common.builder;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class BlockBuilder {
    public static void placeBlock(World world, BlockPos pos, Block block) {
        if (world == null || pos == null || block == null) return;
        world.setBlockState(pos, block.getDefaultState());
    }
}
