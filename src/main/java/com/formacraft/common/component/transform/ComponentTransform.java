package com.formacraft.common.component.transform;

import net.minecraft.util.math.Direction;

/**
 * 构件变换（v1）：facing + mirror。
 *
 * 说明：
 * - mirror 先应用，再应用 rotate（与建议一致，顺序关键）
 * - facing 仅对水平四向有效（N/E/S/W）；其它方向按 SOUTH 处理
 */
public record ComponentTransform(Direction facing, Mirror mirror) {
    public static final ComponentTransform IDENTITY = new ComponentTransform(Direction.SOUTH, Mirror.NONE);

    public ComponentTransform {
        if (facing == null || !facing.getAxis().isHorizontal()) {
            facing = Direction.SOUTH;
        }
        if (mirror == null) {
            mirror = Mirror.NONE;
        }
    }
}

