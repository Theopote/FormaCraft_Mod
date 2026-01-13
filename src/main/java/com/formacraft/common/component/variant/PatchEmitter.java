package com.formacraft.common.component.variant;

import com.formacraft.common.patch.BlockPatch;

import java.util.ArrayList;
import java.util.List;

/**
 * PatchEmitter（Patch 发射器）v1：将 VoxelGrid 转换为 BlockPatch 列表。
 * <p>
 * 职责：
 * - 遍历 VoxelGrid 中所有 voxel
 * - 为每个 voxel 生成一个 BlockPatch（相对 origin 的偏移）
 * - 过滤掉空气方块（可选）
 */
public final class PatchEmitter {
    private PatchEmitter() {}

    /**
     * 将 VoxelGrid 转换为 BlockPatch 列表（核心入口）。
     * <p>
     * 策略：
     * - 遍历 grid 中所有 voxel
     * - 为每个 voxel 生成一个 BlockPatch（action="place", dx/dy/dz=相对坐标, targetBlock=blockState）
     * - 过滤掉 blockState="minecraft:air"（避免无意义的空气方块）
     */
    public static List<BlockPatch> emit(VoxelGrid grid) {
        if (grid == null) return List.of();

        List<BlockPatch> patches = new ArrayList<>();
        for (Voxel v : grid.all()) {
            String blockState = v.blockState();
            if (blockState == null || blockState.isBlank() || blockState.equalsIgnoreCase("minecraft:air")) {
                continue; // 跳过空气方块
            }

            patches.add(new BlockPatch(
                    BlockPatch.PLACE,
                    v.x(),
                    v.y(),
                    v.z(),
                    blockState
            ));
        }

        return patches;
    }
}
