package com.formacraft.common.component.anchor;

import net.minecraft.util.math.Direction;

/**
 * ComponentAnchor（构件自身的锚点定义）。
 * <p>
 * 这是构件库必须提供的东西。
 * <p>
 * 所有坐标都在 Component 本地空间中。
 * <p>
 * 设计思想：
 * - 门、窗：anchor 在"洞口中心"，facing = OUT
 * - 柱子：anchor 在底部中心，facing = UP
 * - 装饰：anchor 在贴墙面，facing = OUT
 */
public final class ComponentAnchor {
    /** 本地原点（通常是构件的几何中心或基准点） */
    public final int localX;
    public final int localY;
    public final int localZ;

    /** 构件的"朝外法线"（用于对齐 socket.normal） */
    public final Direction facing;

    public ComponentAnchor(int x, int y, int z, Direction facing) {
        this.localX = x;
        this.localY = y;
        this.localZ = z;
        this.facing = facing != null ? facing : Direction.SOUTH;
    }

    /**
     * 从 ComponentDefinition.Anchor 创建 ComponentAnchor
     */
    public static ComponentAnchor fromDefinition(com.formacraft.common.component.ComponentDefinition.Anchor defAnchor) {
        if (defAnchor == null) {
            return new ComponentAnchor(0, 0, 0, Direction.SOUTH);
        }

        Direction facing = Direction.SOUTH;
        if (defAnchor.facing != null) {
            try {
                facing = Direction.valueOf(defAnchor.facing.toUpperCase());
            } catch (IllegalArgumentException e) {
                // 无效的 facing，使用默认值
            }
        }

        return new ComponentAnchor(
                defAnchor.dx,
                defAnchor.dy,
                defAnchor.dz,
                facing
        );
    }
}
