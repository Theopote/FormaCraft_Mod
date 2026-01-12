package com.formacraft.common.component.transform;

import net.minecraft.util.math.Direction;

/**
 * 将方块的 facing 随构件变换进行修正（mirror -> rotate）。
 * <p>
 * v1：仅处理水平四向（N/E/S/W）。UP/DOWN 原样返回。
 */
public final class FacingTransformUtil {
    private FacingTransformUtil() {}

    public static Direction transformFacing(Direction original, Direction fromFacing, ComponentTransform t) {
        if (original == null) return null;
        if (t == null) t = ComponentTransform.IDENTITY;
        if (fromFacing == null || !fromFacing.getAxis().isHorizontal()) fromFacing = Direction.SOUTH;

        Direction d = original;
        if (!d.getAxis().isHorizontal()) {
            return d;
        }

        // 1) mirror（在局部坐标的 X/Z 轴意义上）
        if (t.mirror() == Mirror.X) {
            if (d == Direction.EAST) d = Direction.WEST;
            else if (d == Direction.WEST) d = Direction.EAST;
        } else if (t.mirror() == Mirror.Z) {
            if (d == Direction.NORTH) d = Direction.SOUTH;
            else if (d == Direction.SOUTH) d = Direction.NORTH;
        }

        // 2) rotate（fromFacing -> t.facing）
        int steps = ComponentTransformUtil.rotationSteps(fromFacing, t.facing());
        Direction out = d;
        for (int i = 0; i < steps; i++) {
            out = out.rotateYClockwise();
        }
        return out;
    }
}

