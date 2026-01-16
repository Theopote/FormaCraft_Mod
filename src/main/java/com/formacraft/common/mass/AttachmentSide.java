package com.formacraft.common.mass;

/**
 * AttachmentSide（附着侧）
 * <p>
 * 定义翼楼体量附着在主体上的哪一侧
 * <p>
 * 这是离散的方向，基于 Minecraft 的坐标系
 */
public enum AttachmentSide {
    /**
     * 左侧（相对于主体的朝向）
     */
    LEFT,

    /**
     * 右侧（相对于主体的朝向）
     */
    RIGHT,

    /**
     * 前方（相对于主体的朝向）
     */
    FRONT,

    /**
     * 后方（相对于主体的朝向）
     */
    BACK
}
