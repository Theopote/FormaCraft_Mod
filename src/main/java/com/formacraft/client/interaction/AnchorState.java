package com.formacraft.client.interaction;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 世界锚点：在 HUD 模式下用于指定 AI 建造/编辑的基准点。
 */
public final class AnchorState {
    private AnchorState() {}

    private static BlockPos anchor;
    private static Direction facing = Direction.NORTH;
    private static long lastSetTimeMs = 0L;

    public static void set(BlockPos pos, Direction facingDir) {
        anchor = pos != null ? pos.toImmutable() : null;
        facing = facingDir != null ? facingDir : Direction.NORTH;
        lastSetTimeMs = System.currentTimeMillis();
    }

    public static BlockPos get() {
        return anchor;
    }

    public static Direction getFacing() {
        return facing;
    }

    public static long getLastSetTimeMs() {
        return lastSetTimeMs;
    }

    public static boolean hasAnchor() {
        return anchor != null;
    }

    public static void clear() {
        anchor = null;
        facing = Direction.NORTH;
        lastSetTimeMs = 0L;
    }
}


