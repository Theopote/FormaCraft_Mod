package com.formacraft.common.cluster;

/**
 * StreetProfile（街道剖面）
 * 
 * K2 核心参数：定义街道的布局特征
 */
public record StreetProfile(
        /** 建筑排数（每侧） */
        int laneCount,
        
        /** 相邻建筑排之间距离 */
        int laneSpacing,
        
        /** 中央道路宽度 */
        int roadWidth,
        
        /** 是否左右对称 */
        boolean symmetric
) {
    /**
     * 简单街道（单排，普通村路）
     */
    public static StreetProfile simple() {
        return new StreetProfile(1, 6, 4, true);
    }

    /**
     * 商业街（双排，主街）
     */
    public static StreetProfile boulevard() {
        return new StreetProfile(2, 6, 6, true);
    }

    /**
     * 城市大道（三排）
     */
    public static StreetProfile avenue() {
        return new StreetProfile(3, 6, 8, true);
    }

    /**
     * 城墙走廊（单排，无间距，窄通道）
     */
    public static StreetProfile wallCorridor() {
        return new StreetProfile(1, 0, 2, false);
    }

    /**
     * 中轴线（单侧）
     */
    public static StreetProfile processionalAxis() {
        return new StreetProfile(1, 6, 4, false);
    }

    /**
     * 创建对称版本
     */
    public StreetProfile withSymmetric(boolean symmetric) {
        return new StreetProfile(laneCount, laneSpacing, roadWidth, symmetric);
    }

    /**
     * 创建不同排数版本
     */
    public StreetProfile withLaneCount(int laneCount) {
        return new StreetProfile(laneCount, laneSpacing, roadWidth, symmetric);
    }
}

