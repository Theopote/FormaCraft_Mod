package com.formacraft.common.mass;

/**
 * MassOperation（体量组合操作）
 * <p>
 * 体量组合的数学关系
 * <p>
 * ⚠️ 注意：
 * - 这是逻辑关系
 * - 不是几何布尔运算
 * - 只决定"这个体量是否覆盖这个方块位置"
 */
public enum MassOperation {
    /**
     * 叠加（ADD）
     * <p>
     * 规则：如果这个体量的 footprint 和 height 包含 (x, y, z)，则允许放置方块
     */
    ADD,

    /**
     * 相减（SUBTRACT）
     * <p>
     * 规则：如果这个体量的 footprint 和 height 包含 (x, y, z)，则不允许放置方块
     * <p>
     * 用途：天井、中庭、空洞
     */
    SUBTRACT,

    /**
     * 穿插（INTERSECT）
     * <p>
     * 规则：只有在所有 INTERSECT 体量都包含 (x, y, z) 时，才允许放置方块
     * <p>
     * 用途：悬挑、嵌入、重叠区域
     */
    INTERSECT
}
