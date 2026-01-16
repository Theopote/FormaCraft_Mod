package com.formacraft.common.llm.dto.structural;

/**
 * HeightProfile（墙体高度轮廓）
 * <p>
 * 墙体高度的关键抽象
 */
public class HeightProfile {
    /** 墙底高度（通常 = floorPlate.baseY） */
    public final double baseY;

    /** 墙顶高度 */
    public final double topY;

    /** 是否允许变化（坡屋顶 / 台阶） */
    public final boolean variable;

    public HeightProfile(double baseY, double topY, boolean variable) {
        this.baseY = baseY;
        this.topY = topY;
        this.variable = variable;
    }

    /**
     * 创建固定高度的轮廓
     */
    public static HeightProfile fixed(double baseY, double height) {
        return new HeightProfile(baseY, baseY + height, false);
    }

    /**
     * 获取高度差
     */
    public double height() {
        return topY - baseY;
    }
}
