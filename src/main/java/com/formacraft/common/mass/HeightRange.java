package com.formacraft.common.mass;

/**
 * HeightRange（高度范围）
 * <p>
 * 体量的垂直范围（Y 方向）
 * <p>
 * 这是层高、层数的根，直接支持：
 * - 层高
 * - 层数
 * - 悬挑（baseY > 地面）
 * <p>
 * ⚠️ 注意：这是离散的整数范围，基于方块位置（Y 坐标）
 */
public class HeightRange {
    /** 底部 Y 坐标（包含） */
    public final int baseY;

    /** 顶部 Y 坐标（包含） */
    public final int topY;

    public HeightRange(int baseY, int topY) {
        this.baseY = baseY;
        this.topY = topY;
    }

    /**
     * 检查 Y 坐标是否在范围内
     */
    public boolean contains(int y) {
        return y >= baseY && y <= topY;
    }

    /**
     * 计算高度（方块数）
     */
    public int height() {
        return Math.max(0, topY - baseY + 1);
    }

    /**
     * 创建指定层数和层高的高度范围
     *
     * @param baseY 底部 Y 坐标
     * @param floorCount 层数
     * @param floorHeight 层高（每层的方块数）
     * @return HeightRange
     */
    public static HeightRange fromFloors(int baseY, int floorCount, int floorHeight) {
        int totalHeight = floorCount * floorHeight - 1; // -1 因为包含边界
        return new HeightRange(baseY, baseY + totalHeight);
    }
}
