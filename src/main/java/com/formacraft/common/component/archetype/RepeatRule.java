package com.formacraft.common.component.archetype;

/**
 * RepeatRule（重复规则）：定义构件是否允许重复拼接，以及重复的约束。
 * <p>
 * 这是"分段重复 / 拉伸"的基础。
 */
public class RepeatRule {
    /**
     * 是否启用重复
     */
    public boolean enabled = false;

    /**
     * 重复轴
     */
    public RepeatAxis axis = RepeatAxis.X;

    /**
     * 最小段数
     */
    public int minSegments = 1;

    /**
     * 最大段数
     */
    public int maxSegments = 10;

    /**
     * 创建禁用的重复规则
     */
    public static RepeatRule disabled() {
        RepeatRule rule = new RepeatRule();
        rule.enabled = false;
        return rule;
    }

    /**
     * 创建启用的重复规则
     */
    public static RepeatRule enabled(RepeatAxis axis, int minSegments, int maxSegments) {
        RepeatRule rule = new RepeatRule();
        rule.enabled = true;
        rule.axis = axis;
        rule.minSegments = minSegments;
        rule.maxSegments = maxSegments;
        return rule;
    }

    /**
     * 创建栏杆的重复规则（X 轴，1-20 段）
     */
    public static RepeatRule forRailing() {
        return enabled(RepeatAxis.X, 1, 20);
    }
}
