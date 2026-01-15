package com.formacraft.common.component.query;

/**
 * ComponentScore（构件评分）：构件与查询的匹配评分。
 * <p>
 * 评分维度：
 * - semanticScore - 语义匹配度（0.0 - 1.0）
 * - contextScore - 上下文匹配度（0.0 - 1.0）
 * - styleScore - 风格匹配度（0.0 - 1.0）
 * - flexibilityScore - 可变形程度（0.0 - 1.0）
 * - totalScore - 总分（加权平均）
 */
public class ComponentScore {
    /**
     * 语义匹配度（标签匹配）
     */
    public double semanticScore = 0.0;

    /**
     * 上下文匹配度（放置上下文、表面侧等）
     */
    public double contextScore = 0.0;

    /**
     * 风格匹配度（风格兼容性）
     */
    public double styleScore = 0.0;

    /**
     * 可变形程度（是否满足约束条件）
     */
    public double flexibilityScore = 0.0;

    /**
     * 总分（加权平均）
     */
    public double totalScore = 0.0;

    /**
     * 构件 ID（用于标识）
     */
    public String componentId;

    /**
     * 创建评分对象
     */
    public ComponentScore(String componentId) {
        this.componentId = componentId;
    }

    /**
     * 计算总分（加权平均）
     * 
     * @param semanticWeight 语义权重（默认 0.3）
     * @param contextWeight 上下文权重（默认 0.4）
     * @param styleWeight 风格权重（默认 0.2）
     * @param flexibilityWeight 可变形权重（默认 0.1）
     */
    public void calculateTotal(
            double semanticWeight,
            double contextWeight,
            double styleWeight,
            double flexibilityWeight
    ) {
        this.totalScore = semanticScore * semanticWeight +
                         contextScore * contextWeight +
                         styleScore * styleWeight +
                         flexibilityScore * flexibilityWeight;
    }

    /**
     * 计算总分（使用默认权重）
     */
    public void calculateTotal() {
        calculateTotal(0.3, 0.4, 0.2, 0.1);
    }

    @Override
    public String toString() {
        return String.format("ComponentScore[%s: total=%.2f, semantic=%.2f, context=%.2f, style=%.2f, flexibility=%.2f]",
                componentId, totalScore, semanticScore, contextScore, styleScore, flexibilityScore);
    }
}
