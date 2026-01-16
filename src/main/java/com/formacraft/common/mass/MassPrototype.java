package com.formacraft.common.mass;

/**
 * MassPrototype（体量原型）
 * <p>
 * 定义体量的基本类型，用于 Building Mass Assembly（体量组合层）
 * <p>
 * 这是 Formacraft 真正缺失的核心中间层的核心概念之一。
 */
public enum MassPrototype {
    /**
     * 方盒子（Block）
     * <p>
     * 最基础的体量类型，矩形六面体
     * <p>
     * 示例：
     * - 主楼体
     * - 标准房间
     */
    BLOCK,

    /**
     * 平板（Slab）
     * <p>
     * 薄板状体量，用于悬挑、平台等
     * <p>
     * 示例：
     * - 悬挑阳台
     * - 平台
     * - 檐廊
     */
    SLAB,

    /**
     * 塔（Tower）
     * <p>
     * 垂直体量，高度显著大于宽度/深度
     * <p>
     * 示例：
     * - 角楼
     * - 钟塔
     * - 瞭望台
     */
    TOWER,

    /**
     * 翼（Wing）
     * <p>
     * 横向延伸的体量，用于建筑组合
     * <p>
     * 示例：
     * - 侧翼
     * - 配殿
     * - 回廊
     */
    WING,

    /**
     * 平台（Platform）
     * <p>
     * 水平平台，通常位于底层或中间层
     * <p>
     * 示例：
     * - 基座
     * - 台基
     * - 平台层
     */
    PLATFORM
}
