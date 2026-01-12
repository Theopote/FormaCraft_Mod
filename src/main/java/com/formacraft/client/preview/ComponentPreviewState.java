package com.formacraft.client.preview;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * Component 预览状态（纯客户端）。
 *
 * - 不真正放置方块
 * - 仅用于 HUD 世界渲染
 */
public final class ComponentPreviewState {
    private ComponentPreviewState() {}

    private static volatile boolean active = false;
    private static volatile List<BlockPos> localBlocks = null;
    private static volatile BlockPos worldAnchor = null;
    private static volatile Direction facing = Direction.SOUTH;

    public static void show(List<BlockPos> local, BlockPos anchor, Direction facingDir) {
        localBlocks = local;
        worldAnchor = anchor != null ? anchor.toImmutable() : null;
        facing = facingDir != null ? facingDir : Direction.SOUTH;
        active = (local != null && !local.isEmpty() && worldAnchor != null);
    }

    public static void clear() {
        active = false;
        localBlocks = null;
        worldAnchor = null;
    }

    public static boolean isActive() {
        return active;
    }

    public static List<BlockPos> getLocalBlocks() {
        return localBlocks;
    }

    public static BlockPos getWorldAnchor() {
        return worldAnchor;
    }

    public static Direction getFacing() {
        return facing;
    }

    public static void setFacing(Direction dir) {
        if (dir != null) facing = dir;
    }
}

