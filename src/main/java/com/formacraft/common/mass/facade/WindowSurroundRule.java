package com.formacraft.common.mass.facade;

/**
 * WindowSurroundRule（窗套规则）
 * <p>
 * 🎯 核心定义：
 * 窗套 = 依附于 WINDOW Socket 的强调构件
 * <p>
 * 规则地位：
 * - 依附于 WINDOW Socket
 * - 不改变窗洞尺寸
 * - 只是"强调边界"
 * <p>
 * 派生规则（v1）：
 * if (windowSocket.layer.role >= STANDARD
 *     && bay.role == PRIMARY) {
 *     allow window surround;
 * }
 * <p>
 * 注意：不是每个窗都有窗套
 */
public record WindowSurroundRule(
        /**
         * 窗套的级别（固定为 ARTICULATION）
         */
        FacadeComponentLevel level,

        /**
         * 是否有眉（顶部装饰）
         */
        boolean top,

        /**
         * 是否有侧边（左右装饰）
         */
        boolean sides,

        /**
         * 是否有窗台（底部装饰）
         */
        boolean sill
) {
    public WindowSurroundRule {
        // 确保 level 始终是 ARTICULATION
        if (level != FacadeComponentLevel.ARTICULATION) {
            throw new IllegalArgumentException("WindowSurroundRule must be ARTICULATION level");
        }
    }

    /**
     * 创建简单的窗套规则（只有顶部）
     */
    public static WindowSurroundRule topOnly() {
        return new WindowSurroundRule(
                FacadeComponentLevel.ARTICULATION,
                true,
                false,
                false
        );
    }

    /**
     * 创建完整的窗套规则（顶部 + 侧边 + 窗台）
     */
    public static WindowSurroundRule full() {
        return new WindowSurroundRule(
                FacadeComponentLevel.ARTICULATION,
                true,
                true,
                true
        );
    }

    /**
     * 创建简化的窗套规则（只有侧边）
     */
    public static WindowSurroundRule sidesOnly() {
        return new WindowSurroundRule(
                FacadeComponentLevel.ARTICULATION,
                false,
                true,
                false
        );
    }
}
