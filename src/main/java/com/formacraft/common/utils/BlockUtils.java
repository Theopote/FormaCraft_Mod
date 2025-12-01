package com.formacraft.common.utils;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class BlockUtils {
    public static boolean isAir(World world, BlockPos pos) {
        return world != null && world.isAir(pos);
    }
}
