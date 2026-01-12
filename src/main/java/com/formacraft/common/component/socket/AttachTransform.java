package com.formacraft.common.component.socket;

import com.formacraft.common.component.transform.Mirror;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * AttachTransform（插入/挂载时的对齐规则）：
 * - anchor：插槽在世界坐标的锚点
 * - facing：最终朝向（通常继承 socket facing）
 * - mirror：镜像模式（与工具/对称兼容）
 * <p>
 * 当前系统主流使用 ComponentTransform(facing, mirror) 来完成坐标与 blockstate facing 变换；
 * 该 record 用于把“安装语义”显式结构化，便于 prompt/bridge/未来编译器接入。
 */
public record AttachTransform(
        BlockPos anchor,
        Direction facing,
        Mirror mirror
) {
    public static AttachTransform of(BlockPos anchor, Direction facing, Mirror mirror) {
        return new AttachTransform(anchor, facing, mirror);
    }
}

