package com.formacraft.common.component.archetype;

/**
 * GeometryArchetype（几何原型）：构件的几何形态类型。
 * <p>
 * 这是给 AI / Generator 的形态提示，不是强约束。
 */
public enum GeometryArchetype {
    /**
     * FLAT_PANEL - 平面面板
     * 例如：门、窗、墙板
     */
    FLAT_PANEL,

    /**
     * ARCH - 拱形
     * 例如：拱门、拱窗
     */
    ARCH,

    /**
     * COLUMN - 柱形
     * 例如：柱子、支撑
     */
    COLUMN,

    /**
     * FRAME - 框架
     * 例如：窗框、门框
     */
    FRAME,

    /**
     * ORNAMENT - 装饰
     * 例如：斗拱、飞檐、装饰件
     */
    ORNAMENT,

    /**
     * LINEAR - 线性
     * 例如：栏杆、线条装饰
     */
    LINEAR,

    /**
     * VOLUME - 体积
     * 例如：房间模块、体块
     */
    VOLUME
}
