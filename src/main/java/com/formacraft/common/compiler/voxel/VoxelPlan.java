package com.formacraft.common.compiler.voxel;

import java.util.HashMap;
import java.util.Map;

/**
 * VoxelPlan：组件的"中间表示"（体素计划）。
 * <p>
 * 核心思想：
 * - 组件 ≠ 方块集合，而是"可编译的几何语义"
 * - 使用相对 anchor 的坐标（Vec3i = dx, dy, dz）
 * - 存储语义块（SemanticBlock），不是具体方块 ID
 * <p>
 * 这是整个系统的分水岭设计。
 */
public final class VoxelPlan {
    // 相对 anchor 的体素（key = Vec3i(dx, dy, dz), value = SemanticBlock）
    private final Map<Vec3i, SemanticBlock> blocks = new HashMap<>();

    /**
     * 添加一个体素到计划中
     * @param dx 相对 anchor 的 X 偏移
     * @param dy 相对 anchor 的 Y 偏移
     * @param dz 相对 anchor 的 Z 偏移
     * @param block 语义块
     */
    public void put(int dx, int dy, int dz, SemanticBlock block) {
        blocks.put(new Vec3i(dx, dy, dz), block);
    }

    /**
     * 获取所有体素
     * @return 体素映射（不可修改）
     */
    public Map<Vec3i, SemanticBlock> blocks() {
        return Map.copyOf(blocks);
    }

    /**
     * 检查是否为空
     */
    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    /**
     * 获取体素数量
     */
    public int size() {
        return blocks.size();
    }

    /**
     * 简单的 3D 整数向量（用于体素坐标）
     */
    public record Vec3i(int x, int y, int z) {
        public Vec3i add(int dx, int dy, int dz) {
            return new Vec3i(x + dx, y + dy, z + dz);
        }
    }
}
