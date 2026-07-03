package com.formacraft.common.world;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** World height and chunk readiness helpers for build + patch apply. */
public final class WorldBuildBounds {
    private WorldBuildBounds() {}

    public static int topYInclusive(ServerWorld world) {
        if (world == null) return 319;
        return world.getBottomY() + world.getHeight() - 1;
    }

    public static boolean isInsideWorldHeight(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return false;
        int y = pos.getY();
        return y >= world.getBottomY() && y <= topYInclusive(world);
    }

    public static boolean isChunkReady(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return false;
        return world.isChunkLoaded(pos);
    }
}
