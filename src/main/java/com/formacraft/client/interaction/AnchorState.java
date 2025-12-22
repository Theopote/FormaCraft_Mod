package com.formacraft.client.interaction;

import net.minecraft.util.math.BlockPos;

/**
 * 世界锚点：在 HUD 模式下用于指定 AI 建造/编辑的基准点。
 */
public final class AnchorState {
    private AnchorState() {}

    private static BlockPos anchor;

    public static void set(BlockPos pos) {
        anchor = pos != null ? pos.toImmutable() : null;
    }

    public static BlockPos get() {
        return anchor;
    }

    public static boolean hasAnchor() {
        return anchor != null;
    }

    public static void clear() {
        anchor = null;
    }
}


