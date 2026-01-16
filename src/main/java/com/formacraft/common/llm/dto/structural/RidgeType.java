package com.formacraft.common.llm.dto.structural;

/**
 * RidgeType（屋脊类型）
 * <p>
 * v3 引入：中式屋顶的"脊系统语法"
 * <p>
 * 核心思想：中式屋顶不是"一个屋顶"，而是一个脊的系统
 * <p>
 * ⚠️ 注意：这是结构语义，不是装饰分类
 */
public enum RidgeType {
    /**
     * 正脊（Main Ridge）
     * <p>
     * 沿主轴的最长脊线
     * <p>
     * 示例：
     * - 歇山：顶部横脊
     * - 庑殿：中心横脊（可能较短）
     */
    MAIN_RIDGE,

    /**
     * 垂脊（Hip Ridge）
     * <p>
     * 从正脊两端下落的脊线
     * <p>
     * 示例：
     * - 歇山：正脊两端向下
     * - 庑殿：从中心向四角
     */
    HIP_RIDGE,

    /**
     * 戗脊（Diagonal Ridge）
     * <p>
     * 歇山特有，连接垂脊与檐角
     * <p>
     * 示例：
     * - 歇山：从垂脊中段斜向连接至檐角
     * - 这是歇山的灵魂
     */
    DIAGONAL_RIDGE,

    /**
     * 副脊（Secondary Ridge）
     * <p>
     * 重檐、次脊等
     * <p>
     * 示例：
     * - 重檐：下层檐的正脊
     * - 多脊系统：辅助脊线
     */
    SECONDARY_RIDGE
}
