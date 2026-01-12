package com.formacraft.client.preview;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import com.formacraft.common.component.transform.ComponentTransform;

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
    /** 构件定义时的朝向（局部坐标系的“前方”）。 */
    private static volatile Direction fromFacing = Direction.SOUTH;
    /** 当前要放置到世界的变换（facing+mirror）。 */
    private static volatile ComponentTransform transform = ComponentTransform.IDENTITY;

    public static void show(List<BlockPos> local, BlockPos anchor, Direction defFacing, ComponentTransform t) {
        localBlocks = local;
        worldAnchor = anchor != null ? anchor.toImmutable() : null;
        fromFacing = (defFacing != null && defFacing.getAxis().isHorizontal()) ? defFacing : Direction.SOUTH;
        transform = (t != null) ? t : ComponentTransform.IDENTITY;
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

    public static Direction getFromFacing() {
        return fromFacing;
    }

    public static ComponentTransform getTransform() {
        return transform;
    }

    public static void setTransform(ComponentTransform t) {
        if (t != null) transform = t;
    }
}

