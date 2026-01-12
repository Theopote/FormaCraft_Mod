package com.formacraft.client.preview;

import com.formacraft.common.component.transform.ComponentTransform;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Socket 预览状态（纯客户端）。
 * <p>
 * - 用于显示 socket 的开洞体积（mask）与朝向
 * - 坐标系：socketOriginLocal 是相对 anchor 的局部坐标（与 blocks dx/dy/dz 一致）
 */
public final class ComponentSocketPreviewState {
    private ComponentSocketPreviewState() {}

    private static volatile boolean active = false;
    private static volatile BlockPos anchorWorld = null;
    private static volatile BlockPos socketOriginLocal = null;
    private static volatile int w = 2, h = 3, d = 1;
    private static volatile Direction socketFacingLocal = Direction.SOUTH;
    private static volatile Direction fromFacing = Direction.SOUTH;
    private static volatile ComponentTransform transform = ComponentTransform.IDENTITY;

    public static boolean isActive() {
        return active;
    }

    public static void clear() {
        active = false;
        anchorWorld = null;
        socketOriginLocal = null;
        w = 2; h = 3; d = 1;
        socketFacingLocal = Direction.SOUTH;
        fromFacing = Direction.SOUTH;
        transform = ComponentTransform.IDENTITY;
    }

    public static void show(BlockPos anchorWorldPos,
                            BlockPos socketLocal,
                            int width,
                            int height,
                            int depth,
                            Direction socketFacing,
                            Direction defFromFacing,
                            ComponentTransform t) {
        anchorWorld = anchorWorldPos != null ? anchorWorldPos.toImmutable() : null;
        socketOriginLocal = socketLocal != null ? socketLocal.toImmutable() : null;
        w = Math.max(1, width);
        h = Math.max(1, height);
        d = Math.max(1, depth);
        socketFacingLocal = (socketFacing != null && socketFacing.getAxis().isHorizontal()) ? socketFacing : Direction.SOUTH;
        fromFacing = (defFromFacing != null && defFromFacing.getAxis().isHorizontal()) ? defFromFacing : Direction.SOUTH;
        transform = (t != null) ? t : ComponentTransform.IDENTITY;
        active = (anchorWorld != null && socketOriginLocal != null);
    }

    public static BlockPos getAnchorWorld() {
        return anchorWorld;
    }

    public static BlockPos getSocketOriginLocal() {
        return socketOriginLocal;
    }

    public static int getW() { return w; }
    public static int getH() { return h; }
    public static int getD() { return d; }

    public static Direction getSocketFacingLocal() { return socketFacingLocal; }
    public static Direction getFromFacing() { return fromFacing; }
    public static ComponentTransform getTransform() { return transform; }
}

