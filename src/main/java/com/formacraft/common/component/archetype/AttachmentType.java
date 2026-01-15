package com.formacraft.common.component.archetype;

/**
 * AttachmentType（附着类型）：构件如何附着到建筑结构上。
 * <p>
 * 这是"内外 / 边缘 / 支撑 / 附着面"思想的正式落地。
 */
public enum AttachmentType {
    /**
     * SURFACE - 表面附着
     * 例如：门、窗、壁龛、阳台
     */
    SURFACE,

    /**
     * EDGE - 边缘附着
     * 例如：栏杆、女儿墙、飞檐端头
     */
    EDGE,

    /**
     * POINT - 点附着
     * 例如：柱、斗拱、装饰点
     */
    POINT,

    /**
     * VOLUME - 体积附着
     * 例如：房间模块、体块、整体结构
     */
    VOLUME
}
