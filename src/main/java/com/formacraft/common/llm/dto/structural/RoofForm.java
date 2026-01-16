package com.formacraft.common.llm.dto.structural;

/**
 * RoofForm（屋顶形式）
 * <p>
 * v2 引入：屋顶"结构语义"
 * <p>
 * ⚠️ RoofForm 是"策略选择"，不是几何
 * 真正的几何在 Ridge / Slope 里
 */
public enum RoofForm {
    /**
     * 平屋顶（v1）
     */
    FLAT,

    /**
     * 人字屋顶（两坡）
     */
    GABLED,

    /**
     * 四坡屋顶
     */
    HIP,

    /**
     * 沿主轴的人字（中式 / 教堂）
     */
    AXIAL_GABLED,

    /**
     * 沿主轴的四坡
     */
    AXIAL_HIP,

    // ========== 中式屋顶形式（v3 新增）==========

    /**
     * 歇山顶（XIESHAN）
     * <p>
     * v3 重点：最复杂的中式屋顶形式
     * <p>
     * 结构：
     * - 正脊（Main Ridge）
     * - 垂脊（Hip Ridge）：从正脊两端下落
     * - 戗脊（Diagonal Ridge）：连接垂脊与檐角
     * - 檐线（Eave Line）：外轮廓
     */
    XIESHAN,

    /**
     * 重檐（MULTI_EAVE）
     * <p>
     * v3.5：多层檐
     * <p>
     * 结构：
     * - 上层檐 + 下层檐
     * - 副脊系统
     */
    MULTI_EAVE
}
