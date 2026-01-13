package com.formacraft.common.component.variant;

import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.transform.Mirror;
import net.minecraft.util.math.Direction;

/**
 * TransformApplier（变换应用器）v1：朝向/镜像变换。
 * <p>
 * 职责：
 * - 对 VoxelGrid 中的所有 voxel 应用坐标变换（旋转/镜像）
 * - 同时修正每个 voxel 的 blockState 中的 facing 属性（例如楼梯/门的朝向）
 */
public final class TransformApplier {
    private TransformApplier() {}

    /**
     * 应用变换（核心入口）。
     * <p>
     * 策略：
     * - 创建一个新的 VoxelGrid（避免原地修改）
     * - 依次对每个 voxel 做坐标变换 + blockState facing 修正
     */
    public static VoxelGrid apply(
            VoxelGrid grid,
            Direction targetFacing,
            Mirror mirror,
            Direction originalFacing
    ) {
        if (grid == null) return new VoxelGrid();
        if (targetFacing == null || !targetFacing.getAxis().isHorizontal()) targetFacing = Direction.SOUTH;
        if (originalFacing == null || !originalFacing.getAxis().isHorizontal()) originalFacing = Direction.SOUTH;
        if (mirror == null) mirror = Mirror.NONE;

        ComponentTransform transform = new ComponentTransform(targetFacing, mirror);
        VoxelGrid out = new VoxelGrid();

        for (Voxel v : grid.all()) {
            // 1) 坐标变换（旋转 + 镜像）
            int[] newCoords = transformCoords(v.x(), v.y(), v.z(), originalFacing, transform);

            // 2) blockState facing 修正
            String newBlockState = BlockStateStringUtil.withTransformedFacing(v.blockState(), originalFacing, transform);

            // 3) 创建新 voxel
            Voxel newV = v.transform(newCoords[0], newCoords[1], newCoords[2]);
            newV.setBlockState(newBlockState);
            out.add(newV);
        }

        return out;
    }

    /**
     * 坐标变换（旋转 + 镜像）。
     * <p>
     * v1 简化：
     * - 先镜像（在原始朝向坐标系中）
     * - 再旋转（从 originalFacing 旋转到 targetFacing）
     */
    private static int[] transformCoords(int dx, int dy, int dz, Direction originalFacing, ComponentTransform t) {
        int x = dx;
        int y = dy;
        int z = dz;

        // 1) 镜像（在原始朝向坐标系中）
        if (t.mirror() == Mirror.X) {
            x = -x;
        } else if (t.mirror() == Mirror.Z) {
            z = -z;
        }

        // 2) 旋转（从 originalFacing 旋转到 targetFacing）
        int steps = rotationSteps(originalFacing, t.facing());
        for (int i = 0; i < steps; i++) {
            int tmp = x;
            x = -z;
            z = tmp;
        }

        return new int[]{x, y, z};
    }

    /**
     * 计算从 from 到 to 需要多少次 90° 顺时针旋转。
     */
    private static int rotationSteps(Direction from, Direction to) {
        if (from == null || to == null) return 0;
        if (!from.getAxis().isHorizontal() || !to.getAxis().isHorizontal()) return 0;

        int fromIdx = directionIndex(from);
        int toIdx = directionIndex(to);
        return (toIdx - fromIdx + 4) % 4;
    }

    private static int directionIndex(Direction d) {
        return switch (d) {
            case SOUTH -> 0;
            case WEST -> 1;
            case NORTH -> 2;
            case EAST -> 3;
            default -> 0;
        };
    }
}
