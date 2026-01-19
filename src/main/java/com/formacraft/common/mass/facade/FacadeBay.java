package com.formacraft.common.mass.facade;

import net.minecraft.util.math.Box;

/**
 * FacadeBay（立面柱距）
 * <p>
 * 🎯 核心定义：
 * 柱距 = 立面被"划分"的节奏单位
 * <p>
 * 注意：不是"柱子模型"，而是立面的结构骨架。
 * <p>
 * Bay 决定：
 * - 窗的对齐位置
 * - 窗是否成组
 * - 线脚的断点
 * <p>
 * Bay 是比 Window 更高一级的结构概念。
 */
public record FacadeBay(
        /**
         * Bay 的唯一 ID
         */
        String id,

        /**
         * Bay 的空间范围（XZ 平面，覆盖多层的 Y 范围）
         */
        Box bounds,

        /**
         * Bay 的宽度（block）
         */
        int width,

        /**
         * Bay 覆盖的楼层高度范围（从 baseY 到 topY）
         */
        int baseY,
        int topY,

        /**
         * Bay 的角色
         */
        BayRole role
) {
    /**
     * Bay 的角色
     */
    public enum BayRole {
        /**
         * 主柱距
         * <p>
         * 特点：
         * - 通常更宽
         * - 窗通常成组出现
         * - 更容易加窗套
         */
        PRIMARY,

        /**
         * 次柱距
         * <p>
         * 特点：
         * - 通常较窄
         * - 窗可能单独出现
         * - 装饰较少
         */
        SECONDARY
    }

    /**
     * 检查一个 X 坐标是否在这个 Bay 的范围内
     */
    public boolean containsX(double x) {
        return x >= bounds.minX && x < bounds.maxX;
    }

    /**
     * 检查一个 Z 坐标是否在这个 Bay 的范围内
     */
    public boolean containsZ(double z) {
        return z >= bounds.minZ && z < bounds.maxZ;
    }

    /**
     * 获取 Bay 的中心 X 坐标
     */
    public double centerX() {
        return (bounds.minX + bounds.maxX) / 2.0;
    }

    /**
     * 获取 Bay 的中心 Z 坐标
     */
    public double centerZ() {
        return (bounds.minZ + bounds.maxZ) / 2.0;
    }
}
