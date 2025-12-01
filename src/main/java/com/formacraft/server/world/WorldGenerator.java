package com.formacraft.server.world;

import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class WorldGenerator {
    public static void generateFlatArea(World world, BlockPos start, int size) {
        if (world == null || start == null) return;
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++)
                world.setBlockState(start.add(x, 0, z), Blocks.STONE.getDefaultState());
    }
}
