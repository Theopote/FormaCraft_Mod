package com.formacraft.client.preview;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import com.formacraft.common.component.transform.ComponentTransform;

/**
 * Component 预览状态（纯客户端）。
 * <p>
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
    /** 预览颜色（默认紫色）。 */
    private static volatile float cr = 0.70f, cg = 0.40f, cb = 1.00f, ca = 0.65f;

    public static void show(List<BlockPos> local, BlockPos anchor, Direction defFacing, ComponentTransform t) {
        localBlocks = local;
        worldAnchor = anchor != null ? anchor.toImmutable() : null;
        fromFacing = (defFacing != null && defFacing.getAxis().isHorizontal()) ? defFacing : Direction.SOUTH;
        transform = (t != null) ? t : ComponentTransform.IDENTITY;
        active = (local != null && !local.isEmpty() && worldAnchor != null);
    }

    public static void setColor(float r, float g, float b, float a) {
        cr = clamp01(r);
        cg = clamp01(g);
        cb = clamp01(b);
        ca = clamp01(a);
    }

    public static float getR() { return cr; }
    public static float getG() { return cg; }
    public static float getB() { return cb; }
    public static float getA() { return ca; }

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

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}

