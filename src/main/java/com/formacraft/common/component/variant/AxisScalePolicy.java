package com.formacraft.common.component.variant;

/**
 * AxisScalePolicy（轴向缩放策略）：定义哪些轴可以缩放。
 */
public enum AxisScalePolicy {
    /** 完全固定（雕塑、脊兽） */
    NONE,

    /** 等比缩放 */
    UNIFORM,

    /** X/Z 轴缩放（门 / 窗 / 阳台 - 最常用） */
    XZ,

    /** X/Y/Z 轴独立缩放（极少用） */
    XYZ
}
