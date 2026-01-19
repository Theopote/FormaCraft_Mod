package com.formacraft.common.mass.facade;

/**
 * HorizontalBandRule（水平线脚规则）
 * <p>
 * 🎯 核心定义：
 * 线脚 = 在"楼层边界"上画一条强调线
 * <p>
 * 语义：
 * - 线脚帮助人"读懂立面层次"
 * - 跟 FloorLayer 走，不跟 Window 走
 * <p>
 * 规则：
 * if (layer.role == STANDARD) {
 *     create FLOOR_DIVIDER band;
 * }
 */
public record HorizontalBandRule(
        /**
         * 线脚的级别（固定为 ARTICULATION）
         */
        FacadeComponentLevel level,

        /**
         * 作用高度（Y 坐标）
         */
        int y,

        /**
         * 线脚的角色
         */
        BandRole role,

        /**
         * 线脚的宽度（沿立面方向的长度，block）
         */
        int width
) {
    public HorizontalBandRule {
        // 确保 level 始终是 ARTICULATION
        if (level != FacadeComponentLevel.ARTICULATION) {
            throw new IllegalArgumentException("HorizontalBandRule must be ARTICULATION level");
        }
    }

    /**
     * 线脚的角色
     */
    public enum BandRole {
        /**
         * 楼层分隔线
         * <p>
         * 位于标准层的底部或顶部，用于分隔楼层
         */
        FLOOR_DIVIDER,

        /**
         * 檐口 / 女儿墙
         * <p>
         * 位于建筑顶部，作为檐口或女儿墙的装饰线
         */
        CROWN
    }
}
