package com.formacraft.server.world;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** World height and chunk readiness helpers for build + patch apply. */
public final class WorldBuildBounds {
    private WorldBuildBounds() {}

    public static int topYInclusive(ServerWorld world) {
        if (world == null) return 319;
        return world.getBottomY() + world.getHeight() - 1;
    }

    /** Clamp Y into the world's inclusive buildable range (overworld -64..319, nether 0..127, …). */
    public static int clampY(ServerWorld world, int y) {
        if (world == null) return Math.max(-64, Math.min(319, y));
        int bottom = world.getBottomY();
        int top = topYInclusive(world);
        return Math.max(bottom, Math.min(top, y));
    }

    /**
     * Inclusive [lo, hi] after clamping both ends. When the raw range is entirely
     * outside the world, returns lo &gt; hi so callers can skip the loop.
     */
    public static int[] clampYRange(ServerWorld world, int y0, int y1) {
        int a = Math.min(y0, y1);
        int b = Math.max(y0, y1);
        if (world == null) {
            int lo = Math.max(-64, a);
            int hi = Math.min(319, b);
            return new int[]{lo, hi};
        }
        int bottom = world.getBottomY();
        int top = topYInclusive(world);
        return new int[]{Math.max(bottom, a), Math.min(top, b)};
    }

    public static boolean isInsideWorldHeight(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return false;
        int y = pos.getY();
        return y >= world.getBottomY() && y <= topYInclusive(world);
    }

    public static boolean isChunkReady(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return false;
        return world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }
}
