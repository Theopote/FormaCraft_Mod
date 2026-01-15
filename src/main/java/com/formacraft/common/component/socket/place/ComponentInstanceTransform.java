package com.formacraft.common.component.socket.place;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * ComponentInstanceTransform（给 PatchCompiler 用）。
 * <p>
 * 这是 H3 的最终产物：把"一个抽象的 Socket"，变成"Component 能真正落到世界里的 Patch 原点 + 变换"。
 */
public final class ComponentInstanceTransform {
    /** Patch 原点（世界坐标） */
    public final BlockPos origin;

    /** 朝向（世界） */
    public final Direction facing;

    /** 是否镜像 X 轴 */
    public final boolean mirrorX;

    /** 是否镜像 Z 轴 */
    public final boolean mirrorZ;

    public ComponentInstanceTransform(BlockPos origin,
                                      Direction facing,
                                      boolean mirrorX,
                                      boolean mirrorZ) {
        this.origin = origin != null ? origin.toImmutable() : BlockPos.ORIGIN;
        this.facing = facing != null ? facing : Direction.SOUTH;
        this.mirrorX = mirrorX;
        this.mirrorZ = mirrorZ;
    }

    /**
     * 转换为 ComponentTransform（用于兼容现有代码）
     */
    public com.formacraft.common.component.transform.ComponentTransform toComponentTransform() {
        com.formacraft.common.component.transform.Mirror mirror = com.formacraft.common.component.transform.Mirror.NONE;
        if (mirrorX && mirrorZ) {
            // 如果两个都镜像，可能需要特殊处理（v1 暂不支持）
            mirror = com.formacraft.common.component.transform.Mirror.X;
        } else if (mirrorX) {
            mirror = com.formacraft.common.component.transform.Mirror.X;
        } else if (mirrorZ) {
            mirror = com.formacraft.common.component.transform.Mirror.Z;
        }

        return new com.formacraft.common.component.transform.ComponentTransform(facing, mirror);
    }
}
