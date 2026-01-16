package com.formacraft.common.mass.derived;

/**
 * FloorLayer（楼层语义层）
 * <p>
 * 🎯 核心定位（架构校准 2026-01-14）：
 * 多层建筑下的 Socket 分层 = 把"高度"从纯 Y 值，提升为"楼层语义层（Floor Layer）"。
 * <p>
 * 也就是说：
 * 门 / 窗 / 阳台
 * 不再只看 y 值
 * 而是看：这是第几层、是什么层、承担什么角色
 * <p>
 * ⚠️ 注意：
 * FloorLayer 是"解释层"，不是结构层
 * 不生成方块，只参与规则判断
 */
public class FloorLayer {
    /** 第几层（0 = ground） */
    public final int index;

    /** 层底 Y */
    public final int baseY;

    /** 层顶 Y */
    public final int topY;

    /** 语义角色 */
    public final FloorRole role;

    public FloorLayer(int index, int baseY, int topY, FloorRole role) {
        this.index = index;
        this.baseY = baseY;
        this.topY = topY;
        this.role = role;
    }

    /**
     * 检查 Y 坐标是否在这一层
     */
    public boolean contains(int y) {
        return y >= baseY && y <= topY;
    }

    /**
     * 计算层高（方块数）
     */
    public int height() {
        return Math.max(0, topY - baseY + 1);
    }

    /**
     * 楼层语义角色
     */
    public enum FloorRole {
        /** 首层 */
        GROUND,
        /** 标准层 */
        STANDARD,
        /** 顶层 */
        TOP,
        /** 设备 / 次要层（v2） */
        SERVICE
    }
}
