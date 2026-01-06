package com.formacraft.common.geometry;

/**
 * GeometryIntent（几何语义词汇）
 * 
 * 这是 LLM / Style / Tool 共同理解的"几何语义词汇"
 * 
 * 用于在 Prompt 中直接使用，让 AI 能够表达"厚重""轻盈"等空间概念
 */
public enum GeometryIntent {

    /**
     * 薄（1格）
     */
    THIN,

    /**
     * 厚（2-3格）
     */
    THICK,

    /**
     * 厚重（4+格）
     */
    MASSIVE,

    /**
     * 向外扩
     */
    OUTWARD_FLARE,

    /**
     * 向内收
     */
    INWARD_SETBACK,

    /**
     * 出檐
     */
    OVERHANG,

    /**
     * 向上收分
     */
    TAPER_UP,

    /**
     * 台阶式
     */
    STEP_TIERED,

    /**
     * 中空
     */
    HOLLOW,

    /**
     * 实心
     */
    SOLID
}

