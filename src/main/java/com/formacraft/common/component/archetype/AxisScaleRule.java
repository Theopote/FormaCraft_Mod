package com.formacraft.common.component.archetype;

/**
 * AxisScaleRule（轴向缩放规则）：定义某个轴是否允许缩放，以及缩放范围。
 */
public class AxisScaleRule {
    /**
     * 是否锁定（不允许缩放）
     */
    public boolean locked = false;

    /**
     * 最小缩放比例（如果 locked = false）
     */
    public float min = 0.5f;

    /**
     * 最大缩放比例（如果 locked = false）
     */
    public float max = 2.0f;

    /**
     * 创建锁定的轴（不允许缩放）
     */
    public static AxisScaleRule locked() {
        AxisScaleRule rule = new AxisScaleRule();
        rule.locked = true;
        return rule;
    }

    /**
     * 创建允许缩放的轴
     */
    public static AxisScaleRule scalable(float min, float max) {
        AxisScaleRule rule = new AxisScaleRule();
        rule.locked = false;
        rule.min = min;
        rule.max = max;
        return rule;
    }

    /**
     * 创建默认的缩放规则（允许 0.5x 到 2.0x）
     */
    public static AxisScaleRule createDefault() {
        return scalable(0.5f, 2.0f);
    }
}
