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
    AXIAL_HIP
}
