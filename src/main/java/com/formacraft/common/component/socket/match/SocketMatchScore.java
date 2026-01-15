package com.formacraft.common.component.socket.match;

/**
 * SocketMatchScore（Socket 匹配评分）：评分拆分，便于以后调权重。
 * <p>
 * 核心思想：
 * - 将匹配评分拆分为多个维度
 * - 每个维度独立评分，便于调试和调整权重
 * - 最终总分 = 各维度分数之和
 */
public final class SocketMatchScore {
    /** Socket 类型匹配分数 */
    public double typeScore = 0.0;

    /** 尺寸匹配分数 */
    public double sizeScore = 0.0;

    /** Facing 匹配分数 */
    public double facingScore = 0.0;

    /** 上下文匹配分数 */
    public double contextScore = 0.0;

    /** 距离分数（越接近 focus 越好） */
    public double distanceScore = 0.0;

    /**
     * 计算总分
     */
    public double total() {
        return typeScore
             + sizeScore
             + facingScore
             + contextScore
             + distanceScore;
    }

    /**
     * 创建零分
     */
    public static SocketMatchScore zero() {
        return new SocketMatchScore();
    }
}
