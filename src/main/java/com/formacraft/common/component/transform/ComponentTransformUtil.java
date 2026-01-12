package com.formacraft.common.component.transform;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 坐标变换（mirror -> rotate）。
 *
 * 注意：这里的 dx/dz 视为“构件局部坐标”，其轴与世界 X/Z 轴一致；
 * rotate 是围绕 Y 轴做 90°*k 旋转。
 */
public final class ComponentTransformUtil {
    private ComponentTransformUtil() {}

    /**
     * 将构件局部偏移 (dx,dy,dz) 从 fromFacing 旋转到 targetFacing，并可选镜像。
     *
     * - mirror：先应用（翻转 x 或 z）
     * - rotate：再应用（按 facing 差值做 0~3 次顺时针旋转）
     */
    public static BlockPos transformOffset(BlockPos local, Direction fromFacing, ComponentTransform t) {
        if (local == null) return BlockPos.ORIGIN;
        if (t == null) t = ComponentTransform.IDENTITY;
        if (fromFacing == null || !fromFacing.getAxis().isHorizontal()) fromFacing = Direction.SOUTH;

        int x = local.getX();
        int y = local.getY();
        int z = local.getZ();

        // 1) mirror
        switch (t.mirror()) {
            case X -> x = -x;
            case Z -> z = -z;
            case NONE -> {
            }
        }

        // 2) rotate（fromFacing -> t.facing）
        int steps = rotationSteps(fromFacing, t.facing());
        for (int i = 0; i < steps; i++) {
            // 90° clockwise: (x,z) -> (-z, x)
            int nx = -z;
            int nz = x;
            x = nx;
            z = nz;
        }

        return new BlockPos(x, y, z);
    }

    /**
     * 返回从 from 旋转到 to 的顺时针步数（0..3）。
     * 顺时针定义：N->E->S->W。
     */
    public static int rotationSteps(Direction from, Direction to) {
        if (from == null || to == null) return 0;
        if (!from.getAxis().isHorizontal() || !to.getAxis().isHorizontal()) return 0;
        Direction[] order = new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        int a = -1, b = -1;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == from) a = i;
            if (order[i] == to) b = i;
        }
        if (a < 0 || b < 0) return 0;
        int d = b - a;
        if (d < 0) d += 4;
        return d;
    }
}

